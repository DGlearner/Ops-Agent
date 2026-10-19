package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.Ids;
import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.order.OrderService;
import io.github.dglearner.shop.order.redis.RedisFailureLog;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The flash sale's front door (docs/shop-design.md §16.2). A purchase is one Redis script call: no database access, no
 * lock on the stock row, so the requests that cannot succeed (sold out, already bought) are turned away cheaply, and
 * the accepted ones are placed later, at the pace of the queue's consumers.
 */
@Service
public class FlashSaleService {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleService.class);
    private static final Pattern SALE_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** How long a sale read from MySQL is reused; an id with no sale is remembered for a shorter time. */
    private static final long FOUND_TTL = Duration.ofSeconds(5).toNanos();
    private static final long MISSING_TTL = Duration.ofSeconds(1).toNanos();
    /** Bounds the cache against a stream of made-up sale ids. */
    private static final int CACHE_LIMIT = 10_000;

    /** How a purchase went: queued now, an earlier order of the same buyer, or sold out. */
    public sealed interface Purchase {

        record Accepted(FlashSaleOrderView order) implements Purchase {
        }

        record Repeated(FlashSaleOrderView order) implements Purchase {
        }

        record SoldOut() implements Purchase {
        }
    }

    /** A sale as read from MySQL, or null if there is none with that id. */
    private record Cached(FlashSale sale, long expiresAt) {
    }

    private final FlashSaleRepository sales;
    private final FlashSaleStore store;
    private final OrderService orders;
    private final RedisFailureLog redisFailures;
    private final MeterRegistry registry;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> soldOutLogged = ConcurrentHashMap.newKeySet();

    public FlashSaleService(FlashSaleRepository sales, FlashSaleStore store, OrderService orders,
            RedisFailureLog redisFailures, MeterRegistry registry) {
        this.sales = sales;
        this.store = store;
        this.orders = orders;
        this.redisFailures = redisFailures;
        this.registry = registry;
    }

    /** Opens a sale now, for {@code open}: saved in MySQL, then loaded into Redis. */
    public FlashSale create(String sku, int stock, Duration open) {
        Instant now = Instant.now();
        FlashSale sale = new FlashSale(Ids.newId(), sku, stock, now, now.plus(open));
        sales.insert(sale);
        store.load(sale);
        cache.remove(sale.saleId());
        log.atInfo().addKeyValue(LogEvents.ACTION, "flash_sale_created").addKeyValue("flash_sale_id", sale.saleId())
                .addKeyValue("sku", sku).addKeyValue("stock", stock).addKeyValue("ends_at", sale.endsAt())
                .log("Flash sale created");
        return sale;
    }

    public Purchase buy(String saleId, String userId, String paymentToken) {
        openSale(saleId);
        FlashSaleStore.Attempt attempt;
        try {
            attempt = store.tryBuy(saleId, userId, Ids.newId(), paymentToken);
        } catch (DataAccessException e) {
            count("unavailable");
            redisFailures.failed("try_buy", e);
            throw unavailable(saleId);
        }
        return switch (attempt.outcome()) {
            case ACCEPTED -> {
                count("accepted");
                yield new Purchase.Accepted(FlashSaleOrderView.queued(attempt.orderId(), saleId, userId));
            }
            case BOUGHT -> {
                count("repeated");
                yield new Purchase.Repeated(order(saleId, attempt.orderId()));
            }
            case SOLD_OUT -> {
                count("sold_out");
                if (soldOutLogged.add(saleId)) {
                    log.atInfo().addKeyValue(LogEvents.ACTION, "flash_sale_sold_out")
                            .addKeyValue("flash_sale_id", saleId).log("Flash sale sold out");
                }
                yield new Purchase.SoldOut();
            }
            case NO_SALE -> {
                count("not_loaded");
                log.atError().addKeyValue(LogEvents.ACTION, "flash_sale_not_loaded")
                        .addKeyValue("flash_sale_id", saleId).log("Open flash sale is missing from Redis");
                throw unavailable(saleId);
            }
        };
    }

    /** The order as its buyer sees it: QUEUED until the queue reaches it, then the order itself. */
    public FlashSaleOrderView order(String saleId, String orderId) {
        var placed = orders.find(orderId).filter(o -> saleId.equals(o.flashSaleId()));
        if (placed.isPresent()) {
            return FlashSaleOrderView.of(placed.get());
        }
        try {
            return store.buyerOf(saleId, orderId).map(buyer -> FlashSaleOrderView.queued(orderId, saleId, buyer))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                            "No order " + orderId + " in flash sale " + saleId).with("order_id", orderId));
        } catch (DataAccessException e) {
            redisFailures.failed("find_order", e);
            throw unavailable(saleId);
        }
    }

    /** The sale and how many units it still has to give out. */
    public Map<String, Object> status(String saleId) {
        FlashSale sale = sale(saleId);
        try {
            return Map.of("sale", sale, "units_left", store.unitsLeft(saleId));
        } catch (DataAccessException e) {
            redisFailures.failed("units_left", e);
            throw unavailable(saleId);
        }
    }

    private void openSale(String saleId) {
        FlashSale sale = sale(saleId);
        Instant now = Instant.now();
        if (now.isBefore(sale.startsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "FLASH_SALE_NOT_STARTED", "The flash sale has not started")
                    .with("sale_id", saleId).with("starts_at", sale.startsAt());
        }
        if (!sale.openAt(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "FLASH_SALE_ENDED", "The flash sale has ended")
                    .with("sale_id", saleId).with("ends_at", sale.endsAt());
        }
    }

    /**
     * The sale from a short-lived local cache: during a sale every request needs it, and it never changes. Misses for
     * one id are loaded once ({@code compute} holds the others back), and an unknown id is remembered too, so neither
     * a popular sale nor made-up ids send a query per request to MySQL.
     */
    private FlashSale sale(String saleId) {
        if (!SALE_ID.matcher(saleId).matches()) {
            throw notFound(saleId);
        }
        Cached cached = cache.get(saleId);
        if (cached == null || System.nanoTime() - cached.expiresAt() >= 0) {
            if (cache.size() > CACHE_LIMIT) {
                cache.clear();
            }
            cached = cache.compute(saleId, (id, old) -> old != null && System.nanoTime() - old.expiresAt() < 0 ? old
                    : sales.find(id).map(s -> new Cached(s, System.nanoTime() + FOUND_TTL))
                            .orElseGet(() -> new Cached(null, System.nanoTime() + MISSING_TTL)));
        }
        if (cached.sale() == null) {
            throw notFound(saleId);
        }
        return cached.sale();
    }

    private void count(String result) {
        registry.counter("shop.flash_sale.requests", "result", result).increment();
    }

    private static ApiException notFound(String saleId) {
        return new ApiException(HttpStatus.NOT_FOUND, "FLASH_SALE_NOT_FOUND", "No flash sale " + saleId)
                .with("sale_id", saleId);
    }

    private static ApiException unavailable(String saleId) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FLASH_SALE_UNAVAILABLE",
                "The flash sale cannot take orders right now; try again shortly").with("sale_id", saleId);
    }
}
