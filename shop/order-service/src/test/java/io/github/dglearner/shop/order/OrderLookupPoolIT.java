package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.order.order.Order;
import io.github.dglearner.shop.order.order.OrderStatus;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Order lookups with a single database connection. A lookup that needs a second connection while it still holds the
 * first can never get it here, so it fails after the pool's connection timeout instead of answering.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.order.recovery.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=1000"})
class OrderLookupPoolIT extends OrderTestSupport {

    @Test
    void placesReplaysAndReadsOrdersWithOneConnection() throws Exception {
        String key = newKey();

        HttpResponse<String> created = placeOrder(key, "u-pool", oneItem(), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        Order order = json.readValue(created.body(), Order.class);
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.items()).hasSize(1);

        HttpResponse<String> replayed = placeOrder(key, "u-pool", oneItem(), null);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(json.readValue(replayed.body(), Order.class).items()).hasSize(1);

        assertThat(order(order.orderId()).items()).hasSize(1);
    }

    @Test
    void concurrentLookupsShareTheOneConnection() throws Exception {
        String orderId = json.readValue(placeOrder(newKey(), "u-pool", oneItem(), null).body(), Order.class).orderId();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<HttpResponse<String>>> lookups = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                lookups.add(pool.submit(() -> get("/api/v1/orders/" + orderId)));
            }
            for (Future<HttpResponse<String>> lookup : lookups) {
                HttpResponse<String> response = lookup.get();
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            }
        }
    }
}
