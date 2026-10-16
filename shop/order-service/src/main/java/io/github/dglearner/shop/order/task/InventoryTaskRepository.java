package io.github.dglearner.shop.order.task;

import io.github.dglearner.shop.order.OrderProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryTaskRepository {

    /**
     * The first attempt is made right away by the request that settled the order; the retry job only picks a task up
     * after this lease, i.e. if that attempt never recorded a result (e.g. the service crashed).
     */
    static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcClient jdbc;
    private final long maxBackoffMillis;

    public InventoryTaskRepository(JdbcClient jdbc, OrderProperties properties) {
        this.jdbc = jdbc;
        this.maxBackoffMillis = properties.recovery().maxBackoff().toMillis();
    }

    /** Must run in the same transaction as the order status change it belongs to. */
    public void insert(String orderId, InventoryAction action) {
        jdbc.sql("""
                        INSERT INTO inventory_task (order_id, action, status, next_attempt_at, created_at, updated_at)
                        VALUES (:id, :action, 'PENDING', NOW(3) + INTERVAL :lease MICROSECOND, NOW(3), NOW(3))""")
                .param("id", orderId).param("action", action.name()).param("lease", LEASE.toNanos() / 1000)
                .update();
    }

    /** Pending tasks whose next attempt is due, oldest first. */
    public List<InventoryTask> findDue(int limit) {
        return jdbc.sql("""
                        SELECT * FROM inventory_task WHERE status = 'PENDING' AND next_attempt_at <= NOW(3)
                        ORDER BY next_attempt_at LIMIT :limit""")
                .param("limit", limit)
                .query((rs, row) -> map(rs))
                .list();
    }

    /** Takes a due task for one attempt; false if another worker took it first. */
    public boolean claim(String orderId) {
        return jdbc.sql("""
                        UPDATE inventory_task SET next_attempt_at = NOW(3) + INTERVAL :lease MICROSECOND
                        WHERE order_id = :id AND status = 'PENDING' AND next_attempt_at <= NOW(3)""")
                .param("id", orderId).param("lease", LEASE.toNanos() / 1000).update() == 1;
    }

    public void markDone(String orderId) {
        jdbc.sql("""
                        UPDATE inventory_task SET status = 'DONE', attempts = attempts + 1, last_error = NULL,
                               updated_at = NOW(3)
                        WHERE order_id = :id""")
                .param("id", orderId).update();
    }

    public void markFailed(String orderId, String error) {
        jdbc.sql("""
                        UPDATE inventory_task SET status = 'FAILED', attempts = attempts + 1, last_error = :error,
                               updated_at = NOW(3)
                        WHERE order_id = :id""")
                .param("id", orderId).param("error", truncate(error)).update();
    }

    public void scheduleRetry(String orderId, String error) {
        // Exponential backoff from 2 s up to max-backoff. MySQL evaluates these assignments left to right, so the
        // backoff reads attempts before the increment.
        jdbc.sql("""
                        UPDATE inventory_task
                        SET next_attempt_at = NOW(3) + INTERVAL LEAST(2000 << LEAST(attempts, 20), :max) * 1000 MICROSECOND,
                            attempts = attempts + 1, last_error = :error, updated_at = NOW(3)
                        WHERE order_id = :id""")
                .param("id", orderId).param("error", truncate(error)).param("max", maxBackoffMillis).update();
    }

    public Optional<InventoryTask> find(String orderId) {
        return jdbc.sql("SELECT * FROM inventory_task WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, row) -> map(rs))
                .optional();
    }

    private static InventoryTask map(ResultSet rs) throws SQLException {
        return new InventoryTask(rs.getString("order_id"), InventoryAction.valueOf(rs.getString("action")),
                rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"));
    }

    private static String truncate(String error) {
        return error.length() <= 255 ? error : error.substring(0, 255);
    }
}
