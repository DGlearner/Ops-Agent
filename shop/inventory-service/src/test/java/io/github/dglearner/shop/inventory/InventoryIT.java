package io.github.dglearner.shop.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dglearner.shop.common.ShopMySql;
import io.github.dglearner.shop.inventory.reservation.Reservation;
import io.github.dglearner.shop.inventory.reservation.ReservationStatus;
import io.github.dglearner.shop.inventory.stock.Stock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reservations against real MySQL over real HTTP, including concurrent buyers. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // Concurrency tests queue many requests on one hot row; give them time instead of failing at 2 s.
        "spring.datasource.hikari.connection-timeout=10000",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 1"})
class InventoryIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = ShopMySql.newContainer();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    private String skuA;
    private String skuB;

    @BeforeEach
    void createSkus() {
        skuA = newSku(10);
        skuB = newSku(10);
    }

    @Test
    void confirmMovesReservedUnitsToSold() throws Exception {
        String order = newOrder();

        HttpResponse<String> reserved = reserve(order, item(skuA, 3), item(skuB, 1));
        assertThat(reserved.statusCode()).isEqualTo(201);
        assertThat(json.readValue(reserved.body(), Reservation.class).status()).isEqualTo(ReservationStatus.RESERVED);
        assertStock(skuA, 10, 7, 3, 0);

        HttpResponse<String> confirmed = post("/api/v1/reservations/" + order + "/confirm", "");
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(json.readValue(confirmed.body(), Reservation.class).status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertStock(skuA, 10, 7, 0, 3);
        assertStock(skuB, 10, 9, 0, 1);

        HttpResponse<String> again = post("/api/v1/reservations/" + order + "/confirm", "");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertStock(skuA, 10, 7, 0, 3);

        assertProblem(post("/api/v1/reservations/" + order + "/release", ""), 409, "RESERVATION_CONFIRMED");
    }

    @Test
    void releaseReturnsReservedUnitsOnceAndBlocksConfirmation() throws Exception {
        String order = newOrder();
        reserve(order, item(skuA, 4));

        assertThat(post("/api/v1/reservations/" + order + "/release", "").statusCode()).isEqualTo(200);
        assertStock(skuA, 10, 10, 0, 0);

        HttpResponse<String> again = post("/api/v1/reservations/" + order + "/release", "");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertStock(skuA, 10, 10, 0, 0);

        assertProblem(post("/api/v1/reservations/" + order + "/confirm", ""), 409, "RESERVATION_RELEASED");
    }

    @Test
    void replayReturnsTheSameReservationWithoutReservingAgain() throws Exception {
        String order = newOrder();
        HttpResponse<String> first = reserve(order, item(skuA, 2), item(skuB, 1));
        // Same items in another order: still the same request.
        HttpResponse<String> replay = reserve(order, item(skuB, 1), item(skuA, 2));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readValue(replay.body(), Reservation.class))
                .isEqualTo(json.readValue(first.body(), Reservation.class));
        assertStock(skuA, 10, 8, 2, 0);

        assertProblem(reserve(order, item(skuA, 5)), 422, "RESERVATION_MISMATCH");
        assertStock(skuA, 10, 8, 2, 0);
    }

    @Test
    void shortageOnOneItemReservesNothing() throws Exception {
        String order = newOrder();

        JsonNode problem = assertProblem(reserve(order, item(skuA, 2), item(skuB, 11)), 409, "INSUFFICIENT_STOCK");

        assertThat(problem.get("sku").asString()).isEqualTo(skuB);
        assertThat(problem.get("requested").asInt()).isEqualTo(11);
        assertThat(problem.get("available").asInt()).isEqualTo(10);
        assertStock(skuA, 10, 10, 0, 0);
        assertProblem(get("/api/v1/reservations/" + order), 404, "RESERVATION_NOT_FOUND");
        // Nothing was stored, so the same order can try again once stock is back.
        assertThat(reserve(order, item(skuA, 2)).statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsUnknownAndDuplicateSkus() throws Exception {
        assertProblem(reserve(newOrder(), item("NO-SUCH-SKU", 1)), 422, "UNKNOWN_SKU");
        assertProblem(reserve(newOrder(), item(skuA, 1), item(skuA, 2)), 400, "DUPLICATE_SKU");
        assertProblem(reserve(newOrder(), item(skuA, 0)), 400, "VALIDATION_FAILED");
        assertStock(skuA, 10, 10, 0, 0);
    }

    @Test
    void releaseBeforeReserveMakesTheLateReservationFail() throws Exception {
        String order = newOrder();

        HttpResponse<String> release = post("/api/v1/reservations/" + order + "/release", "");
        assertThat(release.statusCode()).isEqualTo(200);
        Reservation tombstone = json.readValue(release.body(), Reservation.class);
        assertThat(tombstone.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(tombstone.items()).isEmpty();

        assertProblem(reserve(order, item(skuA, 3)), 409, "RESERVATION_RELEASED");
        assertStock(skuA, 10, 10, 0, 0);
    }

    @Test
    void concurrentBuyersNeverOversell() throws Exception {
        String hot = newSku(20);

        List<Integer> statuses = concurrently(60, i -> reserve(newOrder(), item(hot, 1)).statusCode());

        Map<Integer, Long> counts = statuses.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        assertThat(counts).containsOnly(Map.entry(201, 20L), Map.entry(409, 40L));
        assertStock(hot, 20, 0, 20, 0);
    }

    @Test
    void ordersLockingSkusInOppositeOrderDoNotDeadlock() throws Exception {
        String x = newSku(1000);
        String y = newSku(1000);

        List<Integer> statuses = concurrently(80, i -> {
            String order = newOrder();
            HttpResponse<String> reserved = i % 2 == 0
                    ? reserve(order, item(x, 1), item(y, 1))
                    : reserve(order, item(y, 1), item(x, 1));
            // Half of the orders settle while others are still reserving, touching the same rows.
            String settle = i % 4 < 2 ? "confirm" : "release";
            int settled = post("/api/v1/reservations/" + order + "/" + settle, "").statusCode();
            return reserved.statusCode() * 1000 + settled;
        });

        assertThat(statuses).containsOnly(201_200);
        assertStock(x, 1000, 960, 0, 40);
        assertStock(y, 1000, 960, 0, 40);
    }

    @Test
    void concurrentDuplicatesReserveOnce() throws Exception {
        String order = newOrder();

        List<Integer> statuses = concurrently(20, i -> reserve(order, item(skuA, 3)).statusCode());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(19);
        assertStock(skuA, 10, 7, 3, 0);
    }

    @Test
    void ledgerConstraintRejectsAnyUnbalancedUpdate() {
        // MySQL reports CHECK violations as error 3819, which Spring leaves uncategorized.
        assertThatThrownBy(() -> jdbc.sql("UPDATE stock SET available = available + 1 WHERE sku = ?")
                .param(skuA).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("stock_balanced");
        assertThatThrownBy(() -> jdbc.sql("UPDATE stock SET available = available - 11, sold = sold + 11 WHERE sku = ?")
                .param(skuA).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("stock_non_negative");
    }

    @Test
    void restockAddsToTotalAndAvailable() throws Exception {
        reserve(newOrder(), item(skuA, 4));

        HttpResponse<String> restocked = post("/api/v1/stock/" + skuA + "/restock", "{\"quantity\":5}");

        assertThat(restocked.statusCode()).isEqualTo(200);
        assertStock(skuA, 15, 11, 4, 0);
        assertProblem(post("/api/v1/stock/NO-SUCH-SKU/restock", "{\"quantity\":5}"), 404, "UNKNOWN_SKU");
    }

    @Test
    void seedsTheCatalogueStockAndExposesBusinessMetrics() throws Exception {
        Stock limited = json.readValue(get("/api/v1/stock/SKU-2001").body(), Stock.class);
        assertThat(limited.total()).isEqualTo(20);

        reserve(newOrder(), item(skuA, 1));
        assertProblem(reserve(newOrder(), item(skuA, 100)), 409, "INSUFFICIENT_STOCK");

        String metrics = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(metrics)
                .contains("shop_inventory_operations_total{application=\"inventory-service\",operation=\"reserve\",result=\"reserved\"}")
                .contains("result=\"insufficient_stock\"")
                .contains("hikaricp_connections_active");
    }

    @Test
    void heldRowLockMakesReservationsOfThatSkuFailWithoutTouchingOthers() throws Exception {
        HttpResponse<String> locked = management("POST", "{\"sku\":\"" + skuA + "\",\"duration\":\"30s\"}");
        assertThat(locked.statusCode()).as(locked.body()).isEqualTo(200);
        try {
            long start = System.nanoTime();
            assertProblem(reserve(newOrder(), item(skuA, 1)), 503, "DATABASE_UNAVAILABLE");
            assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(900);
            assertThat(reserve(newOrder(), item(skuB, 1)).statusCode()).isEqualTo(201);
            assertThat(management("GET", null).body()).contains(skuA);
            assertThat(management("POST", "{\"sku\":\"NO-SUCH-SKU\",\"duration\":\"1s\"}").statusCode())
                    .isEqualTo(400);
        } finally {
            assertThat(management("DELETE", null).body()).contains("\"released\":1");
        }
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(reserve(newOrder(), item(skuA, 1)).statusCode()).isEqualTo(201));
        assertStock(skuA, 10, 9, 1, 0);
    }

    private HttpResponse<String> management(String method, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/rowlock"));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String newSku(int quantity) {
        String sku = "T-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.sql("INSERT INTO stock (sku, total, available, updated_at) VALUES (?, ?, ?, NOW(3))")
                .params(sku, quantity, quantity).update();
        return sku;
    }

    private static String newOrder() {
        return UUID.randomUUID().toString();
    }

    private static String item(String sku, int quantity) {
        return "{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}";
    }

    private HttpResponse<String> reserve(String orderId, String... items) throws Exception {
        return post("/api/v1/reservations",
                "{\"order_id\":\"" + orderId + "\",\"items\":[" + String.join(",", items) + "]}");
    }

    private void assertStock(String sku, int total, int available, int reserved, int sold) throws Exception {
        Stock stock = json.readValue(get("/api/v1/stock/" + sku).body(), Stock.class);
        assertThat(List.of(stock.total(), stock.available(), stock.reserved(), stock.sold()))
                .as("total, available, reserved, sold of %s", sku)
                .containsExactly(total, available, reserved, sold);
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
        return problem;
    }

    private <T> List<T> concurrently(int tasks, ThrowingFunction<Integer, T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int n = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(n);
                };
                futures.add(pool.submit(call));
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

    @FunctionalInterface
    interface ThrowingFunction<A, R> {
        R apply(A a) throws Exception;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
