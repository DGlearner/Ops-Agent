package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.order.StubServer.Reply;
import io.github.dglearner.shop.order.order.FailureReason;
import io.github.dglearner.shop.order.order.Order;
import io.github.dglearner.shop.order.order.OrderStatus;
import io.github.dglearner.shop.order.task.InventoryTask;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Background recovery with short intervals: every order reaches a final state and every reservation is settled.
 * Recovery works on all orders in the database, so assertions look only at the test's own order ids, and the context
 * is closed afterwards so its scheduler stops before other test classes run.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.inventory.read-timeout=500ms",
        "shop.order.payment.read-timeout=500ms",
        "shop.order.recovery.interval=200ms",
        "shop.order.recovery.reconcile-after=300ms",
        "shop.order.recovery.abandon-after=1s",
        "shop.order.recovery.max-backoff=300ms"})
class OrderRecoveryIT extends OrderTestSupport {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void timedOutChargeThatWentThroughEndsPaidAndConfirmed() throws Exception {
        PAYMENT.on("POST", CHARGE, r -> Reply.json(201, "{\"payment_id\":\"p-1\",\"status\":\"SUCCEEDED\"}")
                .after(Duration.ofMillis(1500)));
        PAYMENT.on("POST", VOID, r -> Reply.json(200, "{\"payment_id\":\"p-1\",\"status\":\"SUCCEEDED\"}"));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), null);

        assertThat(response.statusCode()).isEqualTo(202);
        String orderId = json.readValue(response.body(), Order.class).orderId();
        eventually(() -> {
            assertThat(order(orderId).status()).isEqualTo(OrderStatus.PAID);
            assertThat(tasks.find(orderId)).hasValueSatisfying(t -> assertThat(t.status()).isEqualTo(InventoryTask.DONE));
        });
        assertThat(INVENTORY.received("POST", confirmOf(orderId))).hasSize(1);
        assertThat(INVENTORY.received("POST", releaseOf(orderId))).isEmpty();
    }

    @Test
    void chargeThatNeverHappenedIsVoidedAndTheStockReleased() throws Exception {
        PAYMENT.on("POST", CHARGE, r -> Reply.json(503, "{\"code\":\"SERVICE_UNAVAILABLE\"}"));
        PAYMENT.on("POST", VOID, r -> Reply.json(200, "{\"payment_id\":\"p-2\",\"status\":\"VOIDED\"}"));

        String orderId = json.readValue(placeOrder(newKey(), "u-1", oneItem(), null).body(), Order.class).orderId();

        eventually(() -> {
            Order order = order(orderId);
            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.failureReason()).isEqualTo(FailureReason.PAYMENT_UNAVAILABLE);
            assertThat(INVENTORY.received("POST", releaseOf(orderId))).hasSize(1);
        });
    }

    @Test
    void chargeStillAtTheProviderIsAskedAboutAgainLater() throws Exception {
        PAYMENT.on("POST", CHARGE, r -> Reply.json(503, "{}"));
        AtomicInteger voids = new AtomicInteger();
        // Only this test's orders reach the stub as PAYMENT_UNKNOWN, so the counter is not shared with other tests.
        PAYMENT.on("POST", VOID, r -> voids.incrementAndGet() < 3
                ? Reply.json(200, "{\"payment_id\":\"p-3\",\"status\":\"PROCESSING\"}")
                : Reply.json(200, "{\"payment_id\":\"p-3\",\"status\":\"DECLINED\",\"decline_reason\":\"INSUFFICIENT_FUNDS\"}"));

        String orderId = json.readValue(placeOrder(newKey(), "u-1", oneItem(), null).body(), Order.class).orderId();

        eventually(() -> assertThat(order(orderId).failureReason()).isEqualTo(FailureReason.PAYMENT_DECLINED));
        assertThat(voids.get()).isGreaterThanOrEqualTo(3);
        assertThat(INVENTORY.received("POST", releaseOf(orderId))).hasSize(1);
    }

    @Test
    void inventoryCommandsAreRetriedUntilAcknowledged() throws Exception {
        // The first two confirmations of each order fail.
        Map<String, AtomicInteger> confirms = new ConcurrentHashMap<>();
        INVENTORY.on("POST", CONFIRM, r -> confirms.computeIfAbsent(r.path(), p -> new AtomicInteger())
                .incrementAndGet() <= 2
                ? Reply.json(503, "{\"code\":\"DATABASE_UNAVAILABLE\"}")
                : Reply.json(200, "{\"status\":\"CONFIRMED\"}"));

        Order order = json.readValue(placeOrder(newKey(), "u-1", oneItem(), null).body(), Order.class);

        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        eventually(() -> assertThat(task(order).status()).isEqualTo(InventoryTask.DONE));
        assertThat(task(order).attempts()).isEqualTo(3);
        assertThat(task(order).lastError()).isNull();
        assertThat(INVENTORY.received("POST", confirmOf(order.orderId()))).hasSize(3);
    }

    @Test
    void ordersAbandonedMidFlightAreClosed() throws Exception {
        PAYMENT.on("POST", VOID, r -> Reply.json(200, "{\"payment_id\":\"p-4\",\"status\":\"VOIDED\"}"));
        String beforeReserving = insertOrder("CREATED");
        String afterReserving = insertOrder("STOCK_RESERVED");

        eventually(() -> {
            Order first = order(beforeReserving);
            Order second = order(afterReserving);
            assertThat(first.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(first.failureReason()).isEqualTo(FailureReason.ABANDONED);
            // Its charge may have been sent, so it goes through reconciliation rather than straight to cancelled.
            assertThat(second.failureReason()).isEqualTo(FailureReason.PAYMENT_UNAVAILABLE);
        });
        assertThat(INVENTORY.received("POST", releaseOf(beforeReserving))).hasSize(1);
        assertThat(INVENTORY.received("POST", releaseOf(afterReserving))).hasSize(1);
        assertThat(PAYMENT.received("POST", "/api/v1/payments/" + beforeReserving + "/void")).isEmpty();
        assertThat(metrics())
                .contains("shop_order_recovery_total{action=\"abandoned\",application=\"order-service\",result=\"cancelled\"}")
                .contains("shop_order_recovery_total{action=\"reconcile\",application=\"order-service\",result=\"voided\"}");
    }

    private static String confirmOf(String orderId) {
        return "/api/v1/reservations/" + orderId + "/confirm";
    }

    private static String releaseOf(String orderId) {
        return "/api/v1/reservations/" + orderId + "/release";
    }

    /** An order left behind by a request that died an hour ago. */
    private String insertOrder(String status) {
        String orderId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO orders (order_id, user_id, idempotency_key, request_hash, status, amount_cents,
                                            currency, created_at, updated_at)
                        VALUES (?, 'u-9', ?, REPEAT('0', 64), ?, 9900, 'CNY', NOW(3) - INTERVAL 1 HOUR,
                                NOW(3) - INTERVAL 1 HOUR)""")
                .params(orderId, newKey(), status).update();
        return orderId;
    }

    private static void eventually(ThrowingRunnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }
}
