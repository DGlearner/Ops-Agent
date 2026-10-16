package io.github.dglearner.shop.order.order;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
        jdbc.sql("""
                        INSERT INTO orders (order_id, user_id, idempotency_key, request_hash, status, amount_cents,
                                            currency, created_at, updated_at)
                        VALUES (:id, :user, :key, :hash, 'CREATED', :amount, :currency, NOW(3), NOW(3))""")
                .param("id", orderId).param("user", userId).param("key", idempotencyKey).param("hash", requestHash)
                .param("amount", amountCents).param("currency", currency)
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

    public Optional<Order> find(String orderId) {
        return jdbc.sql("SELECT * FROM orders WHERE order_id = :id")
                .param("id", orderId)
                .query(this::map).optional();
    }

    public Optional<Stored> findByKey(String userId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM orders WHERE user_id = :user AND idempotency_key = :key")
                .param("user", userId).param("key", idempotencyKey)
                .query((rs, row) -> new Stored(map(rs, row), rs.getString("request_hash")))
                .optional();
    }

    private Order map(ResultSet rs, int row) throws SQLException {
        String orderId = rs.getString("order_id");
        String reason = rs.getString("failure_reason");
        return new Order(orderId, rs.getString("user_id"), OrderStatus.valueOf(rs.getString("status")),
                reason == null ? null : FailureReason.valueOf(reason), rs.getLong("amount_cents"),
                rs.getString("currency"), items(orderId), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<OrderItem> items(String orderId) {
        return jdbc.sql("SELECT sku, quantity, unit_price_cents FROM order_item WHERE order_id = :id ORDER BY sku")
                .param("id", orderId)
                .query((rs, row) -> new OrderItem(rs.getString("sku"), rs.getInt("quantity"),
                        rs.getLong("unit_price_cents")))
                .list();
    }
}
