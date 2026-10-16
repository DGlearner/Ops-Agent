package io.github.dglearner.shop.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    /** A stored payment together with the token it was requested with. */
    public record Stored(Payment payment, String paymentToken) {
    }

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims the order with a PROCESSING row; throws {@code DuplicateKeyException} if it already has a payment. */
    public void insertProcessing(String paymentId, ChargeRequest request) {
        jdbc.sql("""
                        INSERT INTO payment (payment_id, order_id, user_id, amount_cents, currency, payment_token, status,
                                             created_at, updated_at)
                        VALUES (:paymentId, :orderId, :userId, :amount, :currency, :token, 'PROCESSING', NOW(3), NOW(3))""")
                .param("paymentId", paymentId)
                .param("orderId", request.orderId())
                .param("userId", request.userId())
                .param("amount", request.amountCents())
                .param("currency", request.currency())
                .param("token", request.paymentToken())
                .update();
    }

    /** Records the provider's answer; only a PROCESSING payment can change. */
    public boolean complete(String orderId, PaymentStatus status, String declineReason) {
        return jdbc.sql("""
                        UPDATE payment SET status = :status, decline_reason = :reason, updated_at = NOW(3)
                        WHERE order_id = :orderId AND status = 'PROCESSING'""")
                .param("orderId", orderId).param("status", status.name()).param("reason", declineReason)
                .update() == 1;
    }

    /** Writes the VOIDED placeholder; throws {@code DuplicateKeyException} if the order already has a payment. */
    public void insertVoided(String paymentId, String orderId) {
        jdbc.sql("""
                        INSERT INTO payment (payment_id, order_id, status, created_at, updated_at)
                        VALUES (:paymentId, :orderId, 'VOIDED', NOW(3), NOW(3))""")
                .param("paymentId", paymentId).param("orderId", orderId).update();
    }

    public Optional<Stored> find(String orderId) {
        return jdbc.sql("SELECT * FROM payment WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(PaymentRepository::map)
                .optional();
    }

    /** PROCESSING charges created more than {@code age} ago, oldest first. */
    public List<Stored> findStaleProcessing(Duration age, int limit) {
        return jdbc.sql("""
                        SELECT * FROM payment
                        WHERE status = 'PROCESSING' AND created_at < NOW(3) - INTERVAL :micros MICROSECOND
                        ORDER BY created_at LIMIT :limit""")
                .param("micros", age.toNanos() / 1000).param("limit", limit)
                .query(PaymentRepository::map)
                .list();
    }

    private static Stored map(ResultSet rs, int row) throws SQLException {
        long amount = rs.getLong("amount_cents");
        return new Stored(new Payment(rs.getString("payment_id"), rs.getString("order_id"), rs.getString("user_id"),
                rs.wasNull() ? null : amount, rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("decline_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
                rs.getString("payment_token"));
    }
}
