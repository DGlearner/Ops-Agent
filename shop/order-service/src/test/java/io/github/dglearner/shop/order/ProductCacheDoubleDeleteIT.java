package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Delayed double delete: the second delete removes an old copy written back before it, but not one written after it.
 * Uses SKU-1008.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.flash-sale.consume=false",
        "shop.product-cache.local=false",
        "shop.product-cache.strategy=DOUBLE_DELETE",
        "shop.product-cache.double-delete-delay=500ms"})
@Import(ProductCacheTestSupport.Race.class)
class ProductCacheDoubleDeleteIT extends ProductCacheTestSupport {

    @Test
    void anOldCopyWrittenBeforeTheSecondDeleteIsRemoved() throws Exception {
        long old = databasePrice("SKU-1008");

        rebuildRacingAnUpdate("SKU-1008", old + 100, Duration.ZERO);

        eventually(() -> assertThat(price("SKU-1008")).isEqualTo(old + 100));
    }

    @Test
    void anOldCopyWrittenAfterTheSecondDeleteStays() throws Exception {
        long old = databasePrice("SKU-1008");

        rebuildRacingAnUpdate("SKU-1008", old + 100, Duration.ofMillis(900));

        assertThat(price("SKU-1008")).as("stale").isEqualTo(old);
        Thread.sleep(1000);
        assertThat(price("SKU-1008")).as("still stale").isEqualTo(old);
        dropRedisEntry("SKU-1008");
    }
}
