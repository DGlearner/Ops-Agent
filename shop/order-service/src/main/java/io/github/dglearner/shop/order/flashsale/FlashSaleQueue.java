package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.order.Order;
import io.github.dglearner.shop.order.order.OrderService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Places the orders a flash sale accepted (docs/shop-design.md §16.3), read from the Redis stream by a consumer group.
 * A message is acknowledged only after its order is placed, so a consumer that dies leaves it pending; once it has
 * been pending for {@code claim-after}, any instance takes it over. Placing is idempotent (the message carries the
 * order id), so a message delivered twice places one order.
 *
 * <p>The consumers are a fixed number of threads, which caps how many flash sale orders reach inventory and payment at
 * once, whatever the burst of buyers: the queue absorbs the peak.
 */
@Component
@ConditionalOnBooleanProperty(name = "shop.flash-sale.consume", matchIfMissing = true)
public class FlashSaleQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleQueue.class);
    /** A message that failed this often is given up: its unit goes back to the sale and it is acknowledged. */
    static final int MAX_DELIVERIES = 20;
    /** An empty read waits this long before the next; a blocking read would need a connection of its own. */
    private static final Duration IDLE = Duration.ofMillis(200);

    private final StreamOperations<String, Object, Object> streams;
    private final FlashSaleStore store;
    private final FlashSaleRepository sales;
    private final OrderService orders;
    private final RedisFailureLog redisFailures;
    private final FlashSaleProperties properties;
    private final String instance = instanceName();
    private final AtomicLong pending = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public FlashSaleQueue(StringRedisTemplate redis, FlashSaleStore store, FlashSaleRepository sales,
            OrderService orders, RedisFailureLog redisFailures, FlashSaleProperties properties,
            MeterRegistry registry) {
        this.streams = redis.opsForStream();
        this.store = store;
        this.sales = sales;
        this.orders = orders;
        this.redisFailures = redisFailures;
        this.properties = properties;
        Gauge.builder("shop.flash_sale.queue.pending", pending, AtomicLong::get)
                .description("accepted flash sale orders delivered to a consumer but not yet acknowledged")
                .register(registry);
    }

    @Override
    public synchronized void start() {
        running = true;
        for (int i = 0; i < properties.consumers(); i++) {
            String name = instance + "-" + i;
            Thread thread = Thread.ofPlatform().name("flash-sale-" + i).daemon().start(() -> consume(name));
            threads.add(thread);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        for (Thread thread : threads) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void consume(String name) {
        boolean groupReady = false;
        boolean ownPending = false;
        Consumer consumer = Consumer.from(FlashSaleStore.GROUP, name);
        StreamReadOptions options = StreamReadOptions.empty().count(properties.batch());
        while (running) {
            try {
                if (!groupReady) {
                    store.createGroup();
                    groupReady = true;
                }
                List<MapRecord<String, Object, Object>> records =
                        read(consumer, options, ownPending ? ReadOffset.from("0") : ReadOffset.lastConsumed());
                if (ownPending) {
                    // One pass over this consumer's own pending messages; anything left goes to reclaim().
                    ownPending = false;
                } else if (records == null || records.isEmpty()) {
                    pause(IDLE);
                    continue;
                }
                records.forEach(this::handle);
            } catch (DataAccessException e) {
                // A read that timed out may still have run once Redis answered again, delivering messages to this
                // consumer whose reply was lost. They are pending under its name: read them back first (ID 0)
                // instead of waiting claim-after for reclaim().
                ownPending = true;
                redisFailures.failed("read_queue", e);
                pause(Duration.ofSeconds(1));
            } catch (RuntimeException e) {
                log.atError().addKeyValue(LogEvents.ACTION, "flash_sale_consumer_failed")
                        .addKeyValue("error_type", e.getClass().getSimpleName())
                        .log("Flash sale consumer failed, continuing: {}", e.getMessage(), e);
                pause(Duration.ofSeconds(1));
            }
        }
    }

    /**
     * Takes over messages that a consumer received but has not acknowledged for {@code claim-after}, e.g. because its
     * instance crashed, or because placing the order failed (MySQL down) and the message was left for later.
     * XCLAIM with the same minimum idle time lets only one instance win each message.
     */
    @Scheduled(fixedDelay = 5, initialDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void reclaim() {
        if (!running) {
            return;
        }
        try {
            pending.set(streams.pending(FlashSaleStore.QUEUE, FlashSaleStore.GROUP).getTotalPendingMessages());
            Duration idle = properties.claimAfter();
            for (PendingMessage message : streams.pending(FlashSaleStore.QUEUE, FlashSaleStore.GROUP,
                    Range.unbounded(), 100, idle)) {
                for (MapRecord<String, Object, Object> record : streams.claim(FlashSaleStore.QUEUE,
                        FlashSaleStore.GROUP, instance + "-reclaim", idle, message.getId())) {
                    if (message.getTotalDeliveryCount() >= MAX_DELIVERIES) {
                        giveUp(record, message.getTotalDeliveryCount());
                    } else {
                        handle(record);
                    }
                }
            }
        } catch (DataAccessException e) {
            redisFailures.failed("reclaim_queue", e);
        }
    }

    /** Places the order and acknowledges the message; on failure leaves it pending for {@link #reclaim}. */
    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        String orderId = (String) fields.get("order_id");
        String saleId = (String) fields.get("sale_id");
        String userId = (String) fields.get("user_id");
        String token = (String) fields.get("payment_token");
        if (orderId == null || saleId == null || userId == null || token == null) {
            log.atError().addKeyValue(LogEvents.ACTION, "flash_sale_message_invalid")
                    .addKeyValue("message_id", record.getId().getValue()).log("Malformed flash sale message dropped");
            acknowledge(record);
            return;
        }
        try {
            Optional<FlashSale> sale = sales.find(saleId);
            Optional<Order> placed = sale.isEmpty() ? Optional.empty()
                    : orders.placeFlashSaleOrder(saleId, sale.get().sku(), orderId, userId, token);
            if (placed.isEmpty()) {
                // No such sale, or the buyer already has another order of it: this order will never exist.
                store.giveBack(saleId, userId, orderId);
            }
            acknowledge(record);
        } catch (DataAccessException e) {
            // MySQL or Redis unreachable: the message stays pending and is taken up again after claim-after.
            log.atWarn().addKeyValue(LogEvents.ACTION, "flash_sale_order_deferred").addKeyValue("order_id", orderId)
                    .addKeyValue("flash_sale_id", saleId).addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Flash sale order not placed yet, will retry: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.atError().addKeyValue(LogEvents.ACTION, "flash_sale_order_failed").addKeyValue("order_id", orderId)
                    .addKeyValue("flash_sale_id", saleId).addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Flash sale order failed, will retry: {}", e.getMessage(), e);
        }
    }

    /** A message that keeps failing: the unit goes back to the sale, so it is not lost to a phantom order. */
    private void giveUp(MapRecord<String, Object, Object> record, long deliveries) {
        Map<Object, Object> fields = record.getValue();
        String orderId = (String) fields.get("order_id");
        String saleId = (String) fields.get("sale_id");
        String userId = (String) fields.get("user_id");
        try {
            if (orderId != null && orders.find(orderId).isEmpty()) {
                store.giveBack(saleId, userId, orderId);
            }
        } catch (DataAccessException e) {
            return; // stays pending: given up on a later round
        }
        log.atError().addKeyValue(LogEvents.ACTION, "flash_sale_order_given_up").addKeyValue("order_id", orderId)
                .addKeyValue("flash_sale_id", saleId).addKeyValue("deliveries", deliveries)
                .log("Flash sale order given up after {} deliveries", deliveries);
        acknowledge(record);
    }

    /**
     * Without waiting: from {@code lastConsumed()}, messages not yet delivered to any consumer of the group; from
     * {@code 0}, those already delivered to this consumer and not acknowledged.
     */
    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> read(Consumer consumer, StreamReadOptions options,
            ReadOffset offset) {
        return streams.read(consumer, options, StreamOffset.create(FlashSaleStore.QUEUE, offset));
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        streams.acknowledge(FlashSaleStore.GROUP, record);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String instanceName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return "order-service@" + host + "-" + ManagementFactory.getRuntimeMXBean().getPid();
    }
}
