package io.github.dglearner.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.common.ShopMySql;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Charges against real MySQL over real HTTP; the simulated provider takes a fixed 150 ms. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.payment.gateway-latency-min=150ms",
        "shop.payment.gateway-latency-max=150ms",
        "shop.payment.resolve-interval=200ms",
        "shop.payment.stale-after=1s"})
class PaymentIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = ShopMySql.newContainer();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void chargesAndLooksUpThePayment() throws Exception {
        String order = newOrder();

        HttpResponse<String> charged = charge(order, 19_800, "tok_ok");

        assertThat(charged.statusCode()).isEqualTo(201);
        Payment payment = json.readValue(charged.body(), Payment.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.amountCents()).isEqualTo(19_800L);
        assertThat(payment.declineReason()).isNull();
        assertThat(charged.body()).doesNotContain("tok_ok").doesNotContain("payment_token");
        assertThat(json.readValue(get("/api/v1/payments/" + order).body(), Payment.class)).isEqualTo(payment);
    }

    @Test
    void declinesSandboxTokens() throws Exception {
        Payment declined = json.readValue(charge(newOrder(), 500, "tok_declined").body(), Payment.class);
        Payment invalid = json.readValue(charge(newOrder(), 500, "tok_unknown").body(), Payment.class);

        assertThat(declined.status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(invalid.declineReason()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void replayReturnsTheStoredResultWithoutCallingTheProviderAgain() throws Exception {
        String order = newOrder();
        HttpResponse<String> first = charge(order, 1_000, "tok_declined");
        double calls = providerCalls();

        HttpResponse<String> replay = charge(order, 1_000, "tok_declined");

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readValue(replay.body(), Payment.class)).isEqualTo(json.readValue(first.body(), Payment.class));
        assertThat(providerCalls()).isEqualTo(calls);
    }

    @Test
    void rejectsTheSameOrderWithDifferentDetails() throws Exception {
        String order = newOrder();
        charge(order, 1_000, "tok_ok");

        assertProblem(charge(order, 2_000, "tok_ok"), 422, "PAYMENT_MISMATCH");
        assertProblem(charge(order, 1_000, "tok_declined"), 422, "PAYMENT_MISMATCH");
        assertThat(rows(order)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCallTheProviderOnce() throws Exception {
        String order = newOrder();
        double calls = providerCalls();

        List<Integer> statuses = concurrently(20, () -> charge(order, 4_200, "tok_ok").statusCode());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        // Duplicates that arrive while the provider is working get 409; later ones replay the result.
        assertThat(statuses).filteredOn(s -> s == 409).isNotEmpty();
        assertThat(statuses).allMatch(s -> s == 201 || s == 409 || s == 200);
        assertThat(providerCalls()).isEqualTo(calls + 1);
        assertThat(rows(order)).isEqualTo(1);
        assertThat(charge(order, 4_200, "tok_ok").statusCode()).isEqualTo(200);
    }

    @Test
    void voidBeforeAnyChargeRefusesTheLateCharge() throws Exception {
        String order = newOrder();

        HttpResponse<String> voided = post("/api/v1/payments/" + order + "/void", "");
        double calls = providerCalls();
        HttpResponse<String> late = charge(order, 1_000, "tok_ok");

        assertThat(voided.statusCode()).isEqualTo(200);
        Payment placeholder = json.readValue(voided.body(), Payment.class);
        assertThat(placeholder.status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(placeholder.amountCents()).isNull();
        assertProblem(late, 409, "PAYMENT_VOIDED");
        assertThat(providerCalls()).isEqualTo(calls);
        assertThat(json.readValue(post("/api/v1/payments/" + order + "/void", "").body(), Payment.class))
                .isEqualTo(placeholder);
    }

    @Test
    void voidAfterAChargeReportsHowTheChargeEnded() throws Exception {
        String order = newOrder();
        charge(order, 1_000, "tok_ok");

        Payment payment = json.readValue(post("/api/v1/payments/" + order + "/void", "").body(), Payment.class);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void interruptedChargeIsSettledFromTheProvidersRecord() {
        String order = newOrder();
        jdbc.sql("""
                        INSERT INTO payment (payment_id, order_id, user_id, amount_cents, currency, payment_token, status,
                                             created_at, updated_at)
                        VALUES (?, ?, 'u-1', 500, 'CNY', 'tok_declined', 'PROCESSING', NOW(3) - INTERVAL 1 MINUTE,
                                NOW(3) - INTERVAL 1 MINUTE)""")
                .params(UUID.randomUUID().toString(), order).update();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Payment payment = json.readValue(get("/api/v1/payments/" + order).body(), Payment.class);
            assertThat(payment.status()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(payment.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        });
    }

    @Test
    void validatesRequestsAndReportsMissingPayments() throws Exception {
        assertProblem(post("/api/v1/payments", "{\"order_id\":\"o-1\",\"user_id\":\"u-1\",\"currency\":\"CNY\","
                + "\"payment_token\":\"tok_ok\"}"), 400, "VALIDATION_FAILED");
        assertProblem(charge(newOrder(), 0, "tok_ok"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/v1/payments/" + newOrder()), 404, "PAYMENT_NOT_FOUND");
    }

    @Test
    void exposesPaymentMetrics() throws Exception {
        charge(newOrder(), 100, "tok_ok");

        String metrics = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertThat(metrics)
                .contains("shop_payments_total{application=\"payment-service\",reason=\"none\",status=\"succeeded\"}")
                .contains("shop_payment_gateway_seconds_count");
    }

    private double providerCalls() {
        return registry.get("shop.payment.gateway").timer().count();
    }

    private int rows(String order) {
        return jdbc.sql("SELECT COUNT(*) FROM payment WHERE order_id = ?").param(order).query(Integer.class).single();
    }

    private static String newOrder() {
        return UUID.randomUUID().toString();
    }

    private HttpResponse<String> charge(String order, long amount, String token) throws Exception {
        return post("/api/v1/payments", "{\"order_id\":\"" + order + "\",\"user_id\":\"u-1001\",\"amount_cents\":"
                + amount + ",\"currency\":\"CNY\",\"payment_token\":\"" + token + "\"}");
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
    }

    private <T> List<T> concurrently(int tasks, java.util.concurrent.Callable<T> task) throws Exception {
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

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
