package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.common.ShopMySql;
import io.github.dglearner.shop.order.StubServer.Reply;
import io.github.dglearner.shop.order.order.Order;
import io.github.dglearner.shop.order.task.InventoryTask;
import io.github.dglearner.shop.order.task.InventoryTaskRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared by the order-service integration tests: one MySQL container for the whole test run, and HTTP stubs standing
 * in for inventory and payment so each test can script their answers, delays and outages.
 */
abstract class OrderTestSupport {

    @ServiceConnection
    static final MySQLContainer MYSQL = ShopMySql.newContainer();

    static final StubServer INVENTORY = new StubServer();
    static final StubServer PAYMENT = new StubServer();

    static final String RESERVE = "/api/v1/reservations";
    static final String CONFIRM = "/api/v1/reservations/[^/]+/confirm";
    static final String RELEASE = "/api/v1/reservations/[^/]+/release";
    static final String CHARGE = "/api/v1/payments";
    static final String VOID = "/api/v1/payments/[^/]+/void";

    static {
        MYSQL.start();
    }

    final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    JsonMapper json;

    @Autowired
    InventoryTaskRepository tasks;

    @DynamicPropertySource
    static void downstream(DynamicPropertyRegistry registry) {
        registry.add("shop.order.inventory.base-url", INVENTORY::baseUrl);
        registry.add("shop.order.payment.base-url", PAYMENT::baseUrl);
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

    static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    static String oneItem() {
        return "[{\"sku\":\"SKU-1001\",\"quantity\":1}]";
    }

    InventoryTask task(Order order) {
        return tasks.find(order.orderId()).orElseThrow();
    }

    Order order(String orderId) throws Exception {
        return json.readValue(get("/api/v1/orders/" + orderId).body(), Order.class);
    }

    HttpResponse<String> placeOrder(String key, String userId, String items, String token) throws Exception {
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

    JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
        return problem;
    }

    String metrics() throws Exception {
        return http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
