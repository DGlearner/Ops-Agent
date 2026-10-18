package io.github.dglearner.shop.order.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    /** An order with the fingerprint of the request that created it. */
    public record Stored(Order order, String requestHash) {
    }

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@code DuplicateKeyException} if this user already used the idempotency key. */
    public void insert(String orderId, String userId, String idempotencyKey, String requestHash, long amountCents,
            String currency, List<OrderItem> items) {
        insert(orderId, userId, idempotencyKey, requestHash, amountCents, currency, items, null);
    }

    /**
     * An order placed through a flash sale ({@code flashSaleId}, or null for a regular order). Throws
     * {@code DuplicateKeyException} if the order exists, or if this user already has an order of the sale.
     */
    public void insert(String orderId, String userId, String idempotencyKey, String requestHash, long amountCents,
            String currency, List<OrderItem> items, String flashSaleId) {
        jdbc.sql("""
                        INSERT INTO orders (order_id, user_id, idempotency_key, request_hash, status, amount_cents,
                                            currency, flash_sale_id, created_at, updated_at)
                        VALUES (:id, :user, :key, :hash, 'CREATED', :amount, :currency, :sale, NOW(3), NOW(3))""")
                .param("id", orderId).param("user", userId).param("key", idempotencyKey).param("hash", requestHash)
                .param("amount", amountCents).param("currency", currency).param("sale", flashSaleId)
                .update();
        for (OrderItem item : items) {
            jdbc.sql("""
                            INSERT INTO order_item (order_id, sku, quantity, unit_price_cents)
                            VALUES (:id, :sku, :q, :price)""")
                    .param("id", orderId).param("sku", item.sku()).param("q", item.quantity())
                    .param("price", item.unitPriceCents())
                    .update();
        }
    }

    /**
     * Moves the order from {@code from} to {@code to}. Returns false if it is no longer in {@code from}, i.e. someone
     * else (a retry, a background job) moved it first.
     */
    public boolean transition(String orderId, OrderStatus from, OrderStatus to, FailureReason reason) {
        return jdbc.sql("""
                        UPDATE orders SET status = :to, failure_reason = :reason, updated_at = NOW(3)
                        WHERE order_id = :id AND status = :from""")
                .param("id", orderId).param("from", from.name()).param("to", to.name())
                .param("reason", reason == null ? null : reason.name())
                .update() == 1;
    }

    /** Ids and statuses of orders in one of {@code statuses} that have not changed for {@code age}, oldest first. */
    public List<Map.Entry<String, OrderStatus>> findUnchangedFor(Duration age, int limit, OrderStatus... statuses) {
        return jdbc.sql("""
                        SELECT order_id, status FROM orders
                        WHERE status IN (:statuses) AND updated_at < NOW(3) - INTERVAL :micros MICROSECOND
                        ORDER BY updated_at LIMIT :limit""")
                .param("statuses", Arrays.stream(statuses).map(Enum::name).toList())
                .param("micros", age.toNanos() / 1000).param("limit", limit)
                .query((rs, row) -> Map.entry(rs.getString("order_id"), OrderStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /** Pushes the order to the back of the reconciliation queue without changing its status. */
    public void touch(String orderId, OrderStatus status) {
        jdbc.sql("UPDATE orders SET updated_at = NOW(3) WHERE order_id = :id AND status = :status")
                .param("id", orderId).param("status", status.name()).update();
    }

    public Optional<Order> find(String orderId) {
        return jdbc.sql("SELECT * FROM orders WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, row) -> header(rs)).optional()
                .map(this::withItems);
    }

    public Optional<Stored> findByKey(String userId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM orders WHERE user_id = :user AND idempotency_key = :key")
                .param("user", userId).param("key", idempotencyKey)
                .query((rs, row) -> new Stored(header(rs), rs.getString("request_hash")))
                .optional()
                .map(stored -> new Stored(withItems(stored.order()), stored.requestHash()));
    }

    /**
     * The order row without its items. The items are read by a separate query once this one has returned: querying
     * them from inside the row mapper would take a second connection while the first is still held, so with N
     * lookups in flight on a pool of N connections every lookup would wait for a connection that never frees up.
     */
    private static Order header(ResultSet rs) throws SQLException {
        String reason = rs.getString("failure_reason");
        return new Order(rs.getString("order_id"), rs.getString("user_id"),
                OrderStatus.valueOf(rs.getString("status")), reason == null ? null : FailureReason.valueOf(reason),
                rs.getLong("amount_cents"), rs.getString("currency"), List.of(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("flash_sale_id"));
    }

    private Order withItems(Order order) {
        return new Order(order.orderId(), order.userId(), order.status(), order.failureReason(), order.amountCents(),
                order.currency(), items(order.orderId()), order.createdAt(), order.updatedAt(), order.flashSaleId());
    }

    private List<OrderItem> items(String orderId) {
        return jdbc.sql("SELECT sku, quantity, unit_price_cents FROM order_item WHERE order_id = :id ORDER BY sku")
                .param("id", orderId)
                .query((rs, row) -> new OrderItem(rs.getString("sku"), rs.getInt("quantity"),
                        rs.getLong("unit_price_cents")))
                .list();
    }
}
