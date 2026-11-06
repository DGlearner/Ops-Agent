package io.github.dglearner.shop.order.flashsale;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The outbox of units to give back to flash sales in Redis, one row per cancelled flash sale order (§16.4). */
@Repository
public class UnitReturnRepository {

    /** A cancelled order's own thread tries first; the retry job only takes the return after this lease. */
    static final Duration LEASE = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    record UnitReturn(String orderId, String saleId, String userId) {
    }

    private final JdbcClient jdbc;

    public UnitReturnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run in the transaction that cancels the order. Does nothing for a regular order. */
    void insertIfFlashSale(String orderId) {
        jdbc.sql("""
                        INSERT INTO flash_sale_return (order_id, sale_id, user_id, next_attempt_at, created_at)
                        SELECT order_id, flash_sale_id, user_id, NOW(3) + INTERVAL :lease MICROSECOND, NOW(3)
                        FROM orders WHERE order_id = :id AND flash_sale_id IS NOT NULL""")
                .param("id", orderId).param("lease", LEASE.toNanos() / 1000)
                .update();
    }

    Optional<UnitReturn> find(String orderId) {
        return jdbc.sql("SELECT order_id, sale_id, user_id FROM flash_sale_return WHERE order_id = :id")
                .param("id", orderId).query((rs, row) -> map(rs)).optional();
    }

    /** Returns whose next attempt is due, oldest first. */
    List<UnitReturn> findDue(int limit) {
        return jdbc.sql("""
                        SELECT order_id, sale_id, user_id FROM flash_sale_return WHERE next_attempt_at <= NOW(3)
                        ORDER BY next_attempt_at LIMIT :limit""")
                .param("limit", limit).query((rs, row) -> map(rs)).list();
    }

    /** Takes a due return for one attempt; false if another worker took it first. */
    boolean claim(String orderId) {
        return jdbc.sql("""
                        UPDATE flash_sale_return SET next_attempt_at = NOW(3) + INTERVAL :lease MICROSECOND
                        WHERE order_id = :id AND next_attempt_at <= NOW(3)""")
                .param("id", orderId).param("lease", LEASE.toNanos() / 1000).update() == 1;
    }

    void delete(String orderId) {
        jdbc.sql("DELETE FROM flash_sale_return WHERE order_id = :id").param("id", orderId).update();
    }

    void scheduleRetry(String orderId) {
        // Exponential backoff from 2 s up to 30 s. MySQL evaluates these assignments left to right, so the backoff
        // reads attempts before the increment.
        jdbc.sql("""
                        UPDATE flash_sale_return
                        SET next_attempt_at = NOW(3) + INTERVAL LEAST(2000 << LEAST(attempts, 20), :max) * 1000 MICROSECOND,
                            attempts = attempts + 1
                        WHERE order_id = :id""")
                .param("id", orderId).param("max", MAX_BACKOFF.toMillis()).update();
    }

    private static UnitReturn map(ResultSet rs) throws SQLException {
        return new UnitReturn(rs.getString("order_id"), rs.getString("sale_id"), rs.getString("user_id"));
    }
}
