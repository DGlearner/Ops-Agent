package io.github.dglearner.shop.order.task;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryTaskRepository {

    /** Retries back off exponentially: 2, 4, 8 … seconds, at most five minutes apart. */
    private static final int MAX_BACKOFF_SECONDS = 300;

    private final JdbcClient jdbc;

    public InventoryTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run in the same transaction as the order status change it belongs to. */
    public void insert(String orderId, InventoryAction action) {
        jdbc.sql("""
                        INSERT INTO inventory_task (order_id, action, status, next_attempt_at, created_at, updated_at)
                        VALUES (:id, :action, 'PENDING', NOW(3), NOW(3), NOW(3))""")
                .param("id", orderId).param("action", action.name()).update();
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
        // MySQL evaluates these assignments left to right, so the backoff reads attempts before the increment.
        jdbc.sql("""
                        UPDATE inventory_task
                        SET next_attempt_at = DATE_ADD(NOW(3), INTERVAL LEAST(2 << attempts, :max) SECOND),
                            attempts = attempts + 1, last_error = :error, updated_at = NOW(3)
                        WHERE order_id = :id""")
                .param("id", orderId).param("error", truncate(error)).param("max", MAX_BACKOFF_SECONDS).update();
    }

    public Optional<InventoryTask> find(String orderId) {
        return jdbc.sql("SELECT * FROM inventory_task WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, row) -> new InventoryTask(rs.getString("order_id"),
                        InventoryAction.valueOf(rs.getString("action")), rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("last_error")))
                .optional();
    }

    private static String truncate(String error) {
        return error.length() <= 255 ? error : error.substring(0, 255);
    }
}
