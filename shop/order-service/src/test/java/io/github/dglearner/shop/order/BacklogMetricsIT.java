package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Backlog gauges from rows written straight into the database. Other test classes share the database, so the counts
 * are compared before and after; recovery is off, so nothing else changes them meanwhile.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.recovery.enabled=false"})
class BacklogMetricsIT extends OrderTestSupport {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private BacklogMetrics backlog;

    @Autowired
    private MeterRegistry registry;

    @Test
    void countsUnresolvedOrdersAndUnfinishedTasksWithTheOldestAge() throws Exception {
        backlog.refresh();
        double unknownBefore = gauge("shop.orders.unresolved", "payment_unknown");
        double pendingBefore = registry.get("shop.inventory.tasks.pending").gauge().value();
        double failedBefore = registry.get("shop.inventory.tasks.failed").gauge().value();

        insertOrder("PAYMENT_UNKNOWN", 2 * 3600);
        insertOrder("PAYMENT_UNKNOWN", 60);
        insertTask(insertOrder("PAID", 60), "PENDING", 3 * 3600);
        insertTask(insertOrder("CANCELLED", 60), "FAILED", 60);
        backlog.refresh();

        assertThat(gauge("shop.orders.unresolved", "payment_unknown")).isEqualTo(unknownBefore + 2);
        assertThat(registry.get("shop.orders.unresolved.oldest.age").tag("status", "payment_unknown").gauge().value())
                .as("oldest is the order placed two hours ago").isBetween(2 * 3600.0 - 5, 2 * 3600.0 + 120);
        assertThat(registry.get("shop.inventory.tasks.pending").gauge().value()).isEqualTo(pendingBefore + 1);
        assertThat(registry.get("shop.inventory.tasks.failed").gauge().value()).isEqualTo(failedBefore + 1);
        assertThat(registry.get("shop.inventory.tasks.oldest.age").gauge().value())
                .isBetween(3 * 3600.0 - 5, 3 * 3600.0 + 120);
        assertThat(metrics())
                .contains("shop_orders_unresolved{application=\"order-service\",status=\"payment_unknown\"}")
                .contains("shop_inventory_tasks_oldest_age_seconds{application=\"order-service\"}");
    }

    private double gauge(String name, String status) {
        return registry.get(name).tag("status", status).gauge().value();
    }

    private String insertOrder(String status, int ageSeconds) {
        String orderId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO orders (order_id, user_id, idempotency_key, request_hash, status, amount_cents,
                                            currency, created_at, updated_at)
                        VALUES (?, 'u-9', ?, REPEAT('0', 64), ?, 9900, 'CNY', NOW(3) - INTERVAL ? SECOND, NOW(3))""")
                .params(orderId, newKey(), status, ageSeconds).update();
        return orderId;
    }

    private void insertTask(String orderId, String status, int ageSeconds) {
        jdbc.sql("""
                        INSERT INTO inventory_task (order_id, action, status, next_attempt_at, created_at, updated_at)
                        VALUES (?, 'CONFIRM', ?, NOW(3) + INTERVAL 1 HOUR, NOW(3) - INTERVAL ? SECOND, NOW(3))""")
                .params(orderId, status, ageSeconds).update();
    }
}
