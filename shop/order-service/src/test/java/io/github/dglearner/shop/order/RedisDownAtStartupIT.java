package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * order-service starts and serves without Redis (§16.2, §17.2): product pages come from MySQL, flash sales answer 503.
 * Not an {@link OrderTestSupport}, whose Redis connection would win over the dead port configured here; it reuses
 * that class's MySQL container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.recovery.enabled=false",
        "spring.data.redis.port=1"})
class RedisDownAtStartupIT {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OrderTestSupport.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", OrderTestSupport.MYSQL::getUsername);
        registry.add("spring.datasource.password", OrderTestSupport.MYSQL::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void startsAndServesProductPagesFromMySql() throws Exception {
        HttpResponse<String> product = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/products/SKU-1002")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(product.statusCode()).as(product.body()).isEqualTo(200);
        assertThat(json.readTree(product.body()).path("sku").asString()).isEqualTo("SKU-1002");

        HttpResponse<String> sale = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/flash-sales/0190a0b0-0000-7000-8000-000000000000/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"user_id\":\"u-1\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(sale.statusCode()).as("no such sale: answered from MySQL, before Redis").isEqualTo(404);
    }
}
