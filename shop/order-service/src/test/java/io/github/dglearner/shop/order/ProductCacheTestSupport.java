package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.shop.order.catalog.ProductCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Shared by the product cache tests (docs/shop-design.md §17). Each test class uses its own SKUs, so the classes can
 * share MySQL and Redis; {@link Race} lets a test stop a rebuild between its MySQL read and its Redis write.
 */
abstract class ProductCacheTestSupport extends OrderTestSupport {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProductCache cache;

    /** Holds the next rebuild of {@link #sku} after it read MySQL, until {@link #release} counts down. */
    @TestConfiguration
    static class Race {

        static volatile String sku;
        static volatile CountDownLatch read = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        static void arm(String target) {
            read = new CountDownLatch(1);
            release = new CountDownLatch(1);
            sku = target;
        }

        @Bean
        ProductCache.RebuildHook raceHook() {
            return rebuilt -> {
                if (rebuilt.equals(sku)) {
                    sku = null;
                    read.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    JsonNode detail(String sku) throws Exception {
        HttpResponse<String> response = get("/api/v1/products/" + sku);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    long price(String sku) throws Exception {
        return detail(sku).path("price_cents").asLong();
    }

    HttpResponse<String> patch(String sku, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/products/" + sku))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    void setPrice(String sku, long cents) throws Exception {
        HttpResponse<String> response = patch(sku, "{\"price_cents\":" + cents + "}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /** The price in MySQL, without going through the cache. */
    long databasePrice(String sku) {
        return jdbc.sql("SELECT price_cents FROM product WHERE sku = ?").param(sku).query(Long.class).single();
    }

    long pendingInvalidations(String sku) {
        return jdbc.sql("SELECT COUNT(*) FROM product_cache_invalidation WHERE sku = ?").param(sku).query(Long.class)
                .single();
    }

    /**
     * The race of cache aside: a rebuild reads the row, the price changes and the cache is invalidated, then the
     * rebuild writes what it read. Returns the price the rebuilding request answered with (the old one).
     */
    long rebuildRacingAnUpdate(String sku, long newPrice, Duration releaseAfter) throws Exception {
        dropRedisEntry(sku);
        Race.arm(sku);
        try (ExecutorService reader = Executors.newSingleThreadExecutor()) {
            Future<Long> answered = reader.submit(() -> price(sku));
            assertThat(Race.read.await(5, TimeUnit.SECONDS)).as("the rebuild read MySQL").isTrue();
            setPrice(sku, newPrice);
            Thread.sleep(releaseAfter.toMillis());
            Race.release.countDown();
            return answered.get(10, TimeUnit.SECONDS);
        }
    }

    void dropRedisEntry(String sku) {
        redis.delete("shop:product:{" + sku + "}");
    }

    String redisEntry(String sku) {
        return redis.opsForValue().get("shop:product:{" + sku + "}");
    }

    double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    double databaseReads() {
        return registry.find("shop.product.database.reads").counters().stream().mapToDouble(Counter::count).sum();
    }

    static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    static void unpauseRedis() {
        if (Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState()
                .getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    static void eventually(ThrowingRunnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(assertion);
    }
}
