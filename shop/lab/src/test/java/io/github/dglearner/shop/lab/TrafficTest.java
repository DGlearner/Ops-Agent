package io.github.dglearner.shop.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.awaitility.Awaitility;
import tools.jackson.databind.json.JsonMapper;

/** Catalog failures during an outage must not silently cancel an already running traffic stream. */
class TrafficTest {

    @TempDir
    Path dir;

    private final AtomicBoolean available = new AtomicBoolean(true);
    private HttpServer shop;
    private Traffic traffic;

    @BeforeEach
    void startShop() throws Exception {
        shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        shop.createContext("/api/v1/products", exchange -> {
            byte[] body = (available.get() ? "[{\"sku\":\"SKU-1001\"}]" : "{\"code\":\"DATABASE_UNAVAILABLE\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(available.get() ? 200 : 503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        shop.createContext("/api/v1/orders", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"status\":\"CONFIRMED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        shop.start();
        String url = "http://127.0.0.1:" + shop.getAddress().getPort();
        LabProperties properties = new LabProperties(url, url, url, url, url, url, "unused",
                dir.resolve("ground-truth.jsonl"), url, Duration.ofMinutes(10));
        traffic = new Traffic(properties, new CustomerImpact(properties), JsonMapper.builder().build());
    }

    @AfterEach
    void stopShop() {
        if (traffic != null) {
            traffic.stop();
        }
        if (shop != null) {
            shop.stop(0);
        }
    }

    @Test
    void failedStartLeavesNoTrafficAndExplainsHowToRunMysqlDown() {
        available.set(false);

        assertThatThrownBy(() -> traffic.start(3, 0, 0))
                .isInstanceOf(Traffic.CatalogUnavailable.class)
                .hasMessageContaining("mysql-down").hasMessageContaining("先启动流量");
        assertThat(traffic.status()).isEmpty();
    }

    @Test
    void failedAdjustmentPreservesTheExistingRunAndRecoveryAllowsReplacement() {
        traffic.start(3, 0, 0);
        Object startedAt = traffic.status().getFirst().get("started_at");
        long sent = (long) traffic.status().getFirst().get("sent");
        available.set(false);

        assertThatThrownBy(() -> traffic.start(5, 0, 0)).isInstanceOf(Traffic.CatalogUnavailable.class);

        assertThat(traffic.status()).singleElement().satisfies(run -> {
            assertThat(run.get("running")).isEqualTo(true);
            assertThat(run.get("rate")).isEqualTo(3.0);
            assertThat(run.get("started_at")).isEqualTo(startedAt);
        });
        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat((long) traffic.status().getFirst().get("sent")).isGreaterThan(sent));
        available.set(true);
        traffic.start(5, 0, 0);
        assertThat(traffic.status()).singleElement().satisfies(run -> {
            assertThat(run.get("running")).isEqualTo(true);
            assertThat(run.get("rate")).isEqualTo(5.0);
            assertThat(run.get("started_at")).isNotEqualTo(startedAt);
        });
    }
}
