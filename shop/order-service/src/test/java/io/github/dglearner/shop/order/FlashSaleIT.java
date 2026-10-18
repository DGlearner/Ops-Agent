package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.order.StubServer.Reply;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * Flash sales end to end (docs/shop-design.md §16): buyers go through Redis, the queue's consumers place the orders
 * against the inventory and payment stubs, and the reconciliation report agrees with what happened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.recovery.enabled=false"})
class FlashSaleIT extends OrderTestSupport {

    @BeforeEach
    void declineTheDeclinedToken() {
        PAYMENT.on("POST", CHARGE, r -> r.body().contains("tok_declined")
                ? Reply.json(201, "{\"payment_id\":\"p-2\",\"status\":\"DECLINED\",\"decline_reason\":\"INSUFFICIENT_FUNDS\"}")
                : Reply.json(201, "{\"payment_id\":\"p-1\",\"status\":\"SUCCEEDED\"}"));
    }

    @Test
    void concurrentBuyersGetExactlyTheStock() throws Exception {
        String sale = createSale(5);

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String user = "u-" + i;
                futures.add(buyers.submit(() -> buy(sale, user, "tok_ok")));
            }
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
        }

        List<String> accepted = responses.stream().filter(r -> r.statusCode() == 202)
                .map(r -> json.readTree(r.body()).path("order_id").asString()).toList();
        assertThat(accepted).hasSize(5).doesNotHaveDuplicates();
        assertThat(responses.stream().filter(r -> r.statusCode() == 409)
                .map(r -> json.readTree(r.body()).path("code").asString())).hasSize(35).containsOnly("SOLD_OUT");
        eventually(() -> {
            for (String id : accepted) {
                assertThat(status(sale, id)).isEqualTo("PAID");
            }
        });
        assertThat(INVENTORY.received("POST", RESERVE)).hasSize(5);

        JsonNode report = reconcile(sale);
        assertThat(report.path("consistent").asBoolean()).as(report.toString()).isTrue();
        assertThat(report.path("redis").path("units_left").asLong()).isZero();
        assertThat(report.path("redis").path("holders").asLong()).isEqualTo(5);
        assertThat(report.path("queued").asLong()).isZero();
        assertThat(report.path("orders").path("PAID").asLong()).isEqualTo(5);
    }

    @Test
    void theSameBuyerAlwaysGetsTheSameOrder() throws Exception {
        String sale = createSale(3);

        HttpResponse<String> first = buy(sale, "u-1", "tok_ok");
        HttpResponse<String> again = buy(sale, "u-1", "tok_ok");

        assertThat(first.statusCode()).isEqualTo(202);
        String orderId = json.readTree(first.body()).path("order_id").asString();
        assertThat(first.headers().firstValue("Location")).hasValue("/api/v1/flash-sales/" + sale + "/orders/" + orderId);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readTree(again.body()).path("order_id").asString()).isEqualTo(orderId);
        eventually(() -> assertThat(status(sale, orderId)).isEqualTo("PAID"));
        assertThat(json.readTree(buy(sale, "u-1", "tok_ok").body()).path("status").asString()).isEqualTo("PAID");
        assertThat(reconcile(sale).path("redis").path("units_left").asLong()).isEqualTo(2);
    }

    @Test
    void aDeclinedCardGivesTheUnitToTheNextBuyer() throws Exception {
        String sale = createSale(1);

        String declined = json.readTree(buy(sale, "u-a", "tok_declined").body()).path("order_id").asString();
        eventually(() -> assertThat(status(sale, declined)).isEqualTo("CANCELLED"));
        eventually(() -> {
            JsonNode report = reconcile(sale);
            assertThat(report.path("redis").path("units_left").asLong()).isEqualTo(1);
            assertThat(report.path("returns_pending").asLong()).isZero();
        });

        HttpResponse<String> next = buy(sale, "u-b", "tok_ok");
        assertThat(next.statusCode()).isEqualTo(202);
        String paid = json.readTree(next.body()).path("order_id").asString();
        eventually(() -> assertThat(status(sale, paid)).isEqualTo("PAID"));
        assertProblem(buy(sale, "u-c", "tok_ok"), 409, "SOLD_OUT");
        assertThat(reconcile(sale).path("consistent").asBoolean()).isTrue();
    }

    @Test
    void outOfStockInInventoryCancelsAndGivesTheUnitBack() throws Exception {
        INVENTORY.on("POST", RESERVE,
                r -> Reply.json(409, "{\"code\":\"INSUFFICIENT_STOCK\",\"sku\":\"SKU-2001\",\"available\":0}"));
        String sale = createSale(1);

        String orderId = json.readTree(buy(sale, "u-1", "tok_ok").body()).path("order_id").asString();

        eventually(() -> assertThat(status(sale, orderId)).isEqualTo("CANCELLED"));
        eventually(() -> assertThat(reconcile(sale).path("redis").path("units_left").asLong()).isEqualTo(1));
        assertThat(PAYMENT.received("POST", CHARGE)).isEmpty();
    }

    @Test
    void unknownAndEndedSalesAreRefused() throws Exception {
        assertProblem(buy("0190a0b0-0000-7000-8000-000000000000", "u-1", "tok_ok"), 404, "FLASH_SALE_NOT_FOUND");
        assertProblem(buy("not-a-sale", "u-1", "tok_ok"), 404, "FLASH_SALE_NOT_FOUND");

        String sale = createSale(1, 1);
        Thread.sleep(1100);
        assertProblem(buy(sale, "u-1", "tok_ok"), 409, "FLASH_SALE_ENDED");
    }

    @Test
    void withRedisDownTheSaleAnswers503AndRegularOrdersStillWork() throws Exception {
        String sale = createSale(1);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertProblem(buy(sale, "u-1", "tok_ok"), 503, "FLASH_SALE_UNAVAILABLE");
            assertThat(placeOrder(newKey(), "u-2", oneItem(), null).statusCode()).isEqualTo(201);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        // A timeout is not a failure: the script may have run once Redis woke up. The buyer's retry then gets that
        // order (200) rather than a new one (202); one order per buyer makes the retry safe either way.
        var retry = new HttpResponse[1];
        eventually(() -> {
            retry[0] = buy(sale, "u-1", "tok_ok");
            assertThat(retry[0].statusCode()).isIn(200, 202);
        });
        String orderId = json.readTree((String) retry[0].body()).path("order_id").asString();
        eventually(() -> assertThat(status(sale, orderId)).isEqualTo("PAID"));
        assertThat(reconcile(sale).path("consistent").asBoolean()).isTrue();
    }

    private String createSale(int stock) throws Exception {
        return createSale(stock, 600);
    }

    private String createSale(int stock, int seconds) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/flashsales"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"sku\":\"SKU-2001\",\"stock\":" + stock + ",\"seconds\":" + seconds + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("sale_id").asString();
    }

    private HttpResponse<String> buy(String sale, String user, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/flash-sales/" + sale + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"user_id\":\"" + user + "\",\"payment_token\":\"" + token + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String status(String sale, String orderId) throws Exception {
        return json.readTree(get("/api/v1/flash-sales/" + sale + "/orders/" + orderId).body()).path("status").asString();
    }

    private JsonNode reconcile(String sale) throws Exception {
        return json.readTree(http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + managementPort + "/actuator/flashsales/" + sale)).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }

    private static void eventually(ThrowingRunnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }
}
