package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.order.StubServer.Reply;
import io.github.dglearner.shop.order.flashsale.FlashSaleProperties;
import io.github.dglearner.shop.order.flashsale.FlashSaleQueue;
import io.github.dglearner.shop.order.flashsale.FlashSaleRepository;
import io.github.dglearner.shop.order.flashsale.FlashSaleStore;
import io.github.dglearner.shop.order.flashsale.RedisFailureLog;
import io.github.dglearner.shop.order.order.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Flash sale failures (docs/shop-design.md §16.3–16.4): a consumer that dies holding a message, a message delivered
 * twice, and Redis down when a cancelled order's unit must go back. The queue is built by hand here (the application's
 * own consumers are off), so the test decides who reads each message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.flash-sale.consume=false",
        "shop.order.recovery.interval=200ms"})
class FlashSaleRecoveryIT extends OrderTestSupport {

    private static final String QUEUE = "shop:flash-sale:queue";

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    FlashSaleStore store;

    @Autowired
    FlashSaleRepository sales;

    @Autowired
    OrderService orders;

    @Autowired
    RedisFailureLog redisFailures;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcClient jdbc;

    private FlashSaleQueue queue;

    @BeforeEach
    void handBuiltQueue() {
        queue = new FlashSaleQueue(redis, store, sales, orders, redisFailures,
                new FlashSaleProperties(true, 1, 10, Duration.ofMillis(300)), registry);
    }

    @AfterEach
    void stopQueue() {
        queue.stop();
    }

    @Test
    void aMessageHeldByADeadConsumerIsTakenOverAndPlacedOnce() throws Exception {
        String sale = createSale(2);
        String orderId = json.readTree(buy(sale, "u-1", "tok_ok").body()).path("order_id").asString();
        // A consumer reads the message and dies before acknowledging it.
        List<MapRecord<String, Object, Object>> taken = readAs("crashed");
        assertThat(taken).hasSize(1);

        queue.start();
        Thread.sleep(400);
        queue.reclaim();

        eventually(() -> assertThat(status(sale, orderId)).isEqualTo("PAID"));
        assertThat(INVENTORY.received("POST", RESERVE)).hasSize(1);

        // The same message again, as after a crash between placing the order and acknowledging it.
        redis.opsForStream().add(QUEUE, taken.getFirst().getValue());
        eventually(() -> assertThat(redis.opsForStream().pending(QUEUE, "order-service").getTotalPendingMessages())
                .isZero());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE flash_sale_id = ?").param(sale).query(Long.class)
                .single()).isEqualTo(1);
        assertThat(INVENTORY.received("POST", RESERVE)).hasSize(1);
        assertThat(reconcile(sale).path("consistent").asBoolean()).isTrue();
    }

    @Test
    void aUnitThatRedisCouldNotTakeBackIsReturnedLater() throws Exception {
        String sale = createSale(1);
        // Redis goes down while the card is being declined, so the unit cannot go back right after the cancel.
        PAYMENT.on("POST", CHARGE, r -> {
            pauseRedis();
            return Reply.json(201, "{\"payment_id\":\"p-2\",\"status\":\"DECLINED\",\"decline_reason\":\"X\"}");
        });
        String orderId = json.readTree(buy(sale, "u-1", "tok_declined").body()).path("order_id").asString();
        try {
            queue.start();
            eventually(() -> assertThat(status(sale, orderId)).isEqualTo("CANCELLED"));
            assertThat(pendingReturns(sale)).isEqualTo(1);
        } finally {
            unpauseRedis();
        }

        eventually(() -> assertThat(pendingReturns(sale)).isZero());
        JsonNode report = reconcile(sale);
        assertThat(report.path("redis").path("units_left").asLong()).isEqualTo(1);
        assertThat(report.path("consistent").asBoolean()).as(report.toString()).isTrue();
    }

    private List<MapRecord<String, Object, Object>> readAs(String consumer) {
        @SuppressWarnings("unchecked")
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from("order-service", consumer), StreamReadOptions.empty().count(10),
                StreamOffset.create(QUEUE, ReadOffset.lastConsumed()));
        return records;
    }

    private long pendingReturns(String sale) {
        return jdbc.sql("SELECT COUNT(*) FROM flash_sale_return WHERE sale_id = ?").param(sale).query(Long.class)
                .single();
    }

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        if (Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState()
                .getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private String createSale(int stock) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/flashsales"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sku\":\"SKU-2001\",\"stock\":" + stock + ",\"seconds\":600}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("sale_id").asString();
    }

    private HttpResponse<String> buy(String sale, String user, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/flash-sales/" + sale + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"user_id\":\"" + user + "\",\"payment_token\":\"" + token + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String status(String sale, String orderId) throws Exception {
        return json.readTree(get("/api/v1/flash-sales/" + sale + "/orders/" + orderId).body()).path("status").asString();
    }

    private JsonNode reconcile(String sale) throws Exception {
        return json.readTree(http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/flashsales/" + sale)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static void eventually(ThrowingRunnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }
}
