package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.common.ShopMySql;
import io.github.dglearner.shop.order.StubServer.Reply;
import io.github.dglearner.shop.order.order.FailureReason;
import io.github.dglearner.shop.order.order.Order;
import io.github.dglearner.shop.order.order.OrderItem;
import io.github.dglearner.shop.order.order.OrderStatus;
import io.github.dglearner.shop.order.task.InventoryAction;
import io.github.dglearner.shop.order.task.InventoryTask;
import io.github.dglearner.shop.order.task.InventoryTaskRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Order placement against real MySQL, with inventory and payment replaced by HTTP stubs so each test can script
 * their answers, delays and outages. Read timeouts are 500 ms here.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.inventory.connect-timeout=300ms",
        "shop.order.inventory.read-timeout=500ms",
        "shop.order.payment.connect-timeout=300ms",
        "shop.order.payment.read-timeout=500ms"})
class OrderIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = ShopMySql.newContainer();

    static final StubServer INVENTORY = new StubServer();
    static final StubServer PAYMENT = new StubServer();

    private static final String RESERVE = "/api/v1/reservations";
    private static final String CONFIRM = "/api/v1/reservations/[^/]+/confirm";
    private static final String RELEASE = "/api/v1/reservations/[^/]+/release";
    private static final String CHARGE = "/api/v1/payments";
    private static final Duration SLOWER_THAN_TIMEOUT = Duration.ofMillis(1500);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JsonMapper json;

    @Autowired
    private InventoryTaskRepository tasks;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("shop.order.inventory.base-url", INVENTORY::baseUrl);
        registry.add("shop.order.payment.base-url", PAYMENT::baseUrl);
    }

    @AfterAll
    static void closeStubs() {
        INVENTORY.close();
        PAYMENT.close();
    }

    @BeforeEach
    void healthyDependencies() {
        INVENTORY.reset();
        PAYMENT.reset();
        INVENTORY.on("POST", RESERVE, r -> Reply.json(201, "{\"status\":\"RESERVED\"}"));
        INVENTORY.on("POST", CONFIRM, r -> Reply.json(200, "{\"status\":\"CONFIRMED\"}"));
        INVENTORY.on("POST", RELEASE, r -> Reply.json(200, "{\"status\":\"RELEASED\"}"));
        PAYMENT.on("POST", CHARGE, r -> Reply.json(201, "{\"payment_id\":\"p-1\",\"status\":\"SUCCEEDED\"}"));
    }

    @Test
    void paidOrderConfirmsStockAndCarriesOneTraceAcrossServices() throws Exception {
        HttpResponse<String> response = placeOrder(newKey(), "u-1001",
                "[{\"sku\":\"SKU-2001\",\"quantity\":1},{\"sku\":\"SKU-1001\",\"quantity\":2}]", null);

        assertThat(response.statusCode()).isEqualTo(201);
        Order order = json.readValue(response.body(), Order.class);
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.amountCents()).isEqualTo(2 * 9_900 + 19_900);
        assertThat(order.currency()).isEqualTo("CNY");
        assertThat(order.items()).containsExactly(new OrderItem("SKU-1001", 2, 9_900),
                new OrderItem("SKU-2001", 1, 19_900));
        assertThat(response.headers().firstValue("Location")).hasValue("/api/v1/orders/" + order.orderId());

        StubServer.Received reserve = INVENTORY.received("POST", RESERVE).getFirst();
        assertThat(json.readTree(reserve.body()).path("order_id").asString()).isEqualTo(order.orderId());
        assertThat(reserve.body()).contains("{\"sku\":\"SKU-1001\",\"quantity\":2}");
        JsonNode charge = json.readTree(PAYMENT.received("POST", CHARGE).getFirst().body());
        assertThat(charge.path("amount_cents").asLong()).isEqualTo(order.amountCents());
        assertThat(charge.path("payment_token").asString()).isEqualTo("tok_ok");
        assertThat(INVENTORY.received("POST", CONFIRM)).hasSize(1);
        assertThat(task(order)).extracting(InventoryTask::action, InventoryTask::status)
                .containsExactly(InventoryAction.CONFIRM, InventoryTask.DONE);

        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(reserve.headers().get("traceparent")).startsWith("00-" + traceId + "-");
        assertThat(PAYMENT.received("POST", CHARGE).getFirst().headers().get("traceparent"))
                .startsWith("00-" + traceId + "-");
    }

    @Test
    void outOfStockCancelsWithoutCharging() throws Exception {
        INVENTORY.on("POST", RESERVE,
                r -> Reply.json(409, "{\"code\":\"INSUFFICIENT_STOCK\",\"sku\":\"SKU-2001\",\"available\":0}"));

        Order order = json.readValue(placeOrder(newKey(), "u-1", oneItem(), null).body(), Order.class);

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.failureReason()).isEqualTo(FailureReason.OUT_OF_STOCK);
        assertThat(PAYMENT.received("POST", CHARGE)).isEmpty();
        assertThat(INVENTORY.received("POST", RELEASE)).isEmpty();
        assertThat(tasks.find(order.orderId())).isEmpty();
    }

    @Test
    void declinedPaymentReleasesStock() throws Exception {
        PAYMENT.on("POST", CHARGE, r -> Reply.json(201,
                "{\"payment_id\":\"p-2\",\"status\":\"DECLINED\",\"decline_reason\":\"INSUFFICIENT_FUNDS\"}"));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), "tok_declined");

        assertThat(response.statusCode()).isEqualTo(201);
        Order order = json.readValue(response.body(), Order.class);
        assertThat(order.failureReason()).isEqualTo(FailureReason.PAYMENT_DECLINED);
        assertThat(json.readTree(PAYMENT.received("POST", CHARGE).getFirst().body()).path("payment_token").asString())
                .isEqualTo("tok_declined");
        assertThat(INVENTORY.received("POST", RELEASE)).hasSize(1);
        assertThat(task(order).status()).isEqualTo(InventoryTask.DONE);
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameOrderWithoutCallingAnyService() throws Exception {
        String key = newKey();
        HttpResponse<String> first = placeOrder(key, "u-1", oneItem(), null);
        // Same lines in another order is still the same request.
        HttpResponse<String> retry = placeOrder(key, "u-1", oneItem(), "tok_ok");

        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readValue(retry.body(), Order.class)).isEqualTo(json.readValue(first.body(), Order.class));
        assertThat(INVENTORY.received("POST", RESERVE)).hasSize(1);
        assertThat(PAYMENT.received("POST", CHARGE)).hasSize(1);

        assertProblem(placeOrder(key, "u-1", "[{\"sku\":\"SKU-1002\",\"quantity\":1}]", null), 422,
                "IDEMPOTENCY_KEY_REUSED");
        // Keys are per user: another user with the same key places a separate order.
        assertThat(placeOrder(key, "u-2", oneItem(), null).statusCode()).isEqualTo(201);
    }

    @Test
    void concurrentDuplicatesCreateOneOrder() throws Exception {
        INVENTORY.on("POST", RESERVE, r -> Reply.json(201, "{}").after(Duration.ofMillis(300)));
        String key = newKey();

        List<Integer> statuses = concurrently(10, () -> placeOrder(key, "u-1", oneItem(), null).statusCode());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
        assertThat(INVENTORY.received("POST", RESERVE)).hasSize(1);
        assertThat(placeOrder(key, "u-1", oneItem(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void inventoryTimeoutCancelsTheOrderAndReleasesWhatMayHaveBeenReserved() throws Exception {
        INVENTORY.on("POST", RESERVE, r -> Reply.json(201, "{}").after(SLOWER_THAN_TIMEOUT));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), null);

        JsonNode problem = assertProblem(response, 503, "INVENTORY_UNAVAILABLE");
        Order order = order(problem.path("order_id").asString());
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.failureReason()).isEqualTo(FailureReason.INVENTORY_UNAVAILABLE);
        assertThat(INVENTORY.received("POST", RELEASE)).hasSize(1);
        assertThat(PAYMENT.received("POST", CHARGE)).isEmpty();
    }

    @Test
    void releaseThatCannotBeSentStaysPendingForRetry() throws Exception {
        INVENTORY.stop();

        JsonNode problem = assertProblem(placeOrder(newKey(), "u-1", oneItem(), null), 503, "INVENTORY_UNAVAILABLE");

        InventoryTask task = tasks.find(problem.path("order_id").asString()).orElseThrow();
        assertThat(task.action()).isEqualTo(InventoryAction.RELEASE);
        assertThat(task.status()).isEqualTo(InventoryTask.PENDING);
        assertThat(task.attempts()).isEqualTo(1);
        assertThat(task.lastError()).startsWith("NOT_SENT");
    }

    @Test
    void paymentTimeoutKeepsTheStockAndAwaitsReconciliation() throws Exception {
        PAYMENT.on("POST", CHARGE,
                r -> Reply.json(201, "{\"payment_id\":\"p-3\",\"status\":\"SUCCEEDED\"}").after(SLOWER_THAN_TIMEOUT));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), null);

        assertThat(response.statusCode()).isEqualTo(202);
        Order order = json.readValue(response.body(), Order.class);
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
        assertThat(INVENTORY.received("POST", RELEASE)).isEmpty();
        assertThat(INVENTORY.received("POST", CONFIRM)).isEmpty();
        assertThat(tasks.find(order.orderId())).isEmpty();
    }

    @Test
    void paymentErrorIsTreatedAsUnknownNotAsFailure() throws Exception {
        PAYMENT.on("POST", CHARGE, r -> Reply.json(503, "{\"code\":\"DATABASE_UNAVAILABLE\"}"));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), null);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readValue(response.body(), Order.class).status()).isEqualTo(OrderStatus.PAYMENT_UNKNOWN);
    }

    @Test
    void unreachablePaymentCancelsAndReleases() throws Exception {
        PAYMENT.stop();
        try {
            JsonNode problem = assertProblem(placeOrder(newKey(), "u-1", oneItem(), null), 503, "PAYMENT_UNAVAILABLE");

            assertThat(order(problem.path("order_id").asString()).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(INVENTORY.received("POST", RELEASE)).hasSize(1);
        } finally {
            PAYMENT.start();
        }
    }

    @Test
    void failedConfirmationKeepsTheOrderPaidAndTheTaskPending() throws Exception {
        INVENTORY.on("POST", CONFIRM, r -> Reply.json(503, "{\"code\":\"DATABASE_UNAVAILABLE\"}"));

        HttpResponse<String> response = placeOrder(newKey(), "u-1", oneItem(), null);

        assertThat(response.statusCode()).isEqualTo(201);
        Order order = json.readValue(response.body(), Order.class);
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        InventoryTask task = task(order);
        assertThat(task.status()).isEqualTo(InventoryTask.PENDING);
        assertThat(task.lastError()).isEqualTo("ERROR: HTTP 503 DATABASE_UNAVAILABLE");
    }

    @Test
    void rejectsInvalidRequestsBeforeCallingAnyService() throws Exception {
        assertProblem(placeOrder(newKey(), "u-1", "[{\"sku\":\"NO-SUCH\",\"quantity\":1}]", null), 422, "UNKNOWN_SKU");
        assertProblem(placeOrder(null, "u-1", oneItem(), null), 400, "VALIDATION_FAILED");
        assertProblem(placeOrder(newKey(), "u-1",
                "[{\"sku\":\"SKU-1001\",\"quantity\":1},{\"sku\":\"SKU-1001\",\"quantity\":2}]", null), 400,
                "DUPLICATE_SKU");
        assertProblem(placeOrder(newKey(), "u-1", oneItem(), "4111111111111111"), 400, "VALIDATION_FAILED");
        assertProblem(placeOrder(newKey(), "u-1", "[{\"sku\":\"SKU-1001\",\"quantity\":101}]", null), 400,
                "VALIDATION_FAILED");

        assertThat(INVENTORY.received("POST", RESERVE)).isEmpty();
        assertProblem(get("/api/v1/orders/" + UUID.randomUUID()), 404, "ORDER_NOT_FOUND");
    }

    @Test
    void listsTheCatalogueAndExposesMetrics() throws Exception {
        JsonNode products = json.readTree(get("/api/v1/products").body());
        assertThat(products).hasSize(9);
        assertThat(products.get(0).path("price_cents").asLong()).isEqualTo(9_900);

        placeOrder(newKey(), "u-1", oneItem(), null);
        String metrics = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertThat(metrics)
                .contains("shop_orders_total{application=\"order-service\",reason=\"none\",status=\"paid\"}")
                .contains("shop_inventory_tasks_total{action=\"confirm\",application=\"order-service\",result=\"done\"}")
                .containsPattern("http_client_requests_seconds_count\\{[^}]*uri=\"/api/v1/payments\"");
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private static String oneItem() {
        return "[{\"sku\":\"SKU-1001\",\"quantity\":1}]";
    }

    private InventoryTask task(Order order) {
        return tasks.find(order.orderId()).orElseThrow();
    }

    private Order order(String orderId) throws Exception {
        return json.readValue(get("/api/v1/orders/" + orderId).body(), Order.class);
    }

    private HttpResponse<String> placeOrder(String key, String userId, String items, String token) throws Exception {
        String body = "{\"user_id\":\"" + userId + "\",\"items\":" + items
                + (token == null ? "" : ",\"payment_token\":\"" + token + "\"") + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
        return problem;
    }

    private <T> List<T> concurrently(int tasks, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
