package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The product cache with its default, versioned invalidation (docs/shop-design.md §17), at the Redis level (the local
 * level is off here; {@link ProductLocalCacheIT} covers it). Uses SKU-1005 and SKU-1006.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.flash-sale.consume=false",
        "shop.order.recovery.interval=200ms",
        "shop.product-cache.local=false"})
@Import(ProductCacheTestSupport.Race.class)
class ProductCacheIT extends ProductCacheTestSupport {

    @Test
    void theSecondReadComesFromRedis() throws Exception {
        dropRedisEntry("SKU-1005");
        double before = databaseReads();

        long version = detail("SKU-1005").path("version").asLong();
        detail("SKU-1005");

        assertThat(databaseReads() - before).isEqualTo(1);
        assertThat(redisEntry("SKU-1005")).startsWith(version + "|P|");
    }

    @Test
    void anUpdateIsSeenByTheNextRead() throws Exception {
        long before = price("SKU-1005");

        setPrice("SKU-1005", before + 7);

        assertThat(price("SKU-1005")).isEqualTo(before + 7);
    }

    @Test
    void concurrentMissesRebuildOnce() throws Exception {
        dropRedisEntry("SKU-1006");
        double before = counter("shop.product.database.reads", "reason", "rebuild");

        List<Future<Long>> reads = new ArrayList<>();
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                reads.add(readers.submit(() -> price("SKU-1006")));
            }
        }

        long price = databasePrice("SKU-1006");
        for (Future<Long> read : reads) {
            assertThat(read.get()).isEqualTo(price);
        }
        assertThat(counter("shop.product.database.reads", "reason", "rebuild") - before).isEqualTo(1);
    }

    @Test
    void unknownProductsAreTurnedAwayByTheBloomFilter() throws Exception {
        eventually(() -> assertThat(redis.hasKey("shop:product:bloom")).isTrue());
        double before = databaseReads();

        for (int i = 0; i < 20; i++) {
            assertProblem(get("/api/v1/products/NO-SUCH-" + i), 404, "PRODUCT_NOT_FOUND");
        }

        assertThat(databaseReads() - before).isZero();
    }

    @Test
    void aLostBloomFilterLetsRequestsThroughAndIsLoadedAgain() throws Exception {
        eventually(() -> assertThat(redis.hasKey("shop:product:bloom")).isTrue());
        redis.delete("shop:product:bloom");

        // Without the filter every product would look unknown; the marker it lacks tells "missing" from "not there".
        assertThat(detail("SKU-1001").path("sku").asString()).isEqualTo("SKU-1001");
        assertProblem(get("/api/v1/products/NO-SUCH-AFTER-LOSS"), 404, "PRODUCT_NOT_FOUND");
        eventually(() -> assertThat(redis.hasKey("shop:product:bloom")).isTrue());
    }

    @Test
    void aRebuildHoldingTheOldRowCannotOverwriteTheUpdate() throws Exception {
        long old = databasePrice("SKU-1005");
        double refused = counter("shop.product.cache.writes", "result", "refused_older");

        long answered = rebuildRacingAnUpdate("SKU-1005", old + 100, Duration.ZERO);

        assertThat(answered).as("the racing request answered with what it read").isEqualTo(old);
        assertThat(counter("shop.product.cache.writes", "result", "refused_older") - refused).isEqualTo(1);
        assertThat(price("SKU-1005")).isEqualTo(old + 100);
    }

    @Test
    void anInvalidationLostToARedisOutageIsRetried() throws Exception {
        long before = price("SKU-1006");
        pauseRedis();
        try {
            setPrice("SKU-1006", before + 1);
            assertThat(pendingInvalidations("SKU-1006")).isEqualTo(1);
            // Meanwhile reads fall back to MySQL.
            assertThat(price("SKU-1006")).isEqualTo(before + 1);
        } finally {
            unpauseRedis();
        }
        eventually(() -> {
            assertThat(pendingInvalidations("SKU-1006")).isZero();
            assertThat(price("SKU-1006")).isEqualTo(before + 1);
        });
    }

    @Test
    void anUnknownProductCannotBeUpdated() throws Exception {
        HttpResponse<String> response = patch("NO-SUCH-SKU", "{\"price_cents\":100}");
        assertProblem(response, 404, "PRODUCT_NOT_FOUND");
        assertProblem(patch("SKU-1005", "{}"), 400, "NOTHING_TO_CHANGE");
    }
}
