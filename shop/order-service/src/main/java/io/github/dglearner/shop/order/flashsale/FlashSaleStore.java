package io.github.dglearner.shop.order.flashsale;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A flash sale's state in Redis (docs/shop-design.md §16.2), which decides who may buy:
 * <ul>
 *   <li>{@code shop:flash-sale:{id}:left}: units not yet given to a buyer;</li>
 *   <li>{@code shop:flash-sale:{id}:buyers}: buyer to order id, one order per buyer;</li>
 *   <li>{@code shop:flash-sale:{id}:orders}: order id to buyer, to answer "where is my order" before it exists;</li>
 *   <li>{@code shop:flash-sale:queue}: the stream of accepted orders, shared by all sales.</li>
 * </ul>
 * The scripts keep {@code left + buyers = stock} at every moment. A sale's keys share a hash tag, but the queue does
 * not, so this needs a single Redis instance; in a cluster each sale would get its own queue under the same tag.
 */
@Component
public class FlashSaleStore {

    static final String QUEUE = "shop:flash-sale:queue";
    static final String GROUP = "order-service";

    enum Outcome {
        ACCEPTED, BOUGHT, SOLD_OUT, NO_SALE
    }

    /** What the sale said: for ACCEPTED and BOUGHT, the buyer's order id. */
    record Attempt(Outcome outcome, String orderId) {
    }

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TRY_BUY = RedisScript.of(new ClassPathResource("flashsale/try-buy.lua"),
            List.class);
    private static final RedisScript<Long> GIVE_BACK = RedisScript.of(
            new ClassPathResource("flashsale/give-back.lua"), Long.class);

    private final StringRedisTemplate redis;

    public FlashSaleStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Puts a new sale into Redis: all of its stock left, no buyers. */
    void load(FlashSale sale) {
        redis.delete(List.of(key(sale.saleId(), "buyers"), key(sale.saleId(), "orders")));
        redis.opsForValue().set(key(sale.saleId(), "left"), Integer.toString(sale.stock()));
        createGroup();
    }

    /** One atomic step: an earlier order of this buyer, sold out, or a unit taken and the order queued. */
    Attempt tryBuy(String saleId, String userId, String orderId, String paymentToken) {
        List<?> result = redis.execute(TRY_BUY, keys(saleId, QUEUE), userId, orderId, saleId, paymentToken);
        return new Attempt(Outcome.valueOf((String) result.get(0)), (String) result.get(1));
    }

    /** Gives the unit of a cancelled order back; false if it already went back (the script is idempotent). */
    boolean giveBack(String saleId, String userId, String orderId) {
        return Long.valueOf(1).equals(redis.execute(GIVE_BACK, keys(saleId), userId, orderId));
    }

    /** Units not yet given to a buyer, or -1 if the sale is not in Redis. */
    long unitsLeft(String saleId) {
        String left = redis.opsForValue().get(key(saleId, "left"));
        return left == null ? -1 : Long.parseLong(left);
    }

    /** Buyers holding a unit: their order is queued, placed, or cancelled but its unit not given back yet. */
    long holders(String saleId) {
        return redis.opsForHash().size(key(saleId, "buyers"));
    }

    /** The buyer of an order the sale accepted and still counts; the order's row may not exist yet. */
    Optional<String> buyerOf(String saleId, String orderId) {
        return Optional.ofNullable((String) redis.opsForHash().get(key(saleId, "orders"), orderId));
    }

    /** Creates the consumer group (and the stream) unless it exists. */
    void createGroup() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(QUEUE.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0"), true));
        } catch (DataAccessException e) {
            if (!groupExists(e)) {
                throw e;
            }
        }
    }

    /** Redis answers BUSYGROUP when the group exists; the driver's exception sits below Spring's. */
    private static boolean groupExists(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> keys(String saleId, String... more) {
        List<String> keys = new ArrayList<>(List.of(key(saleId, "left"), key(saleId, "buyers"),
                key(saleId, "orders")));
        keys.addAll(List.of(more));
        return keys;
    }

    private static String key(String saleId, String part) {
        return "shop:flash-sale:{" + saleId + "}:" + part;
    }
}
