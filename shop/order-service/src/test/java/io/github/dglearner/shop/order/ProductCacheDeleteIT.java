package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Plain cache aside (update MySQL, then delete the entry) loses the race that the versioned strategy wins
 * ({@link ProductCacheIT}): the old row written back after the delete stays until it expires. Uses SKU-1007.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.flash-sale.consume=false",
        "shop.product-cache.local=false",
        "shop.product-cache.strategy=DELETE"})
@Import(ProductCacheTestSupport.Race.class)
class ProductCacheDeleteIT extends ProductCacheTestSupport {

    @Test
    void aRebuildHoldingTheOldRowLeavesItCached() throws Exception {
        long old = databasePrice("SKU-1007");

        rebuildRacingAnUpdate("SKU-1007", old + 100, Duration.ZERO);

        assertThat(databasePrice("SKU-1007")).isEqualTo(old + 100);
        assertThat(price("SKU-1007")).as("stale").isEqualTo(old);
        Thread.sleep(1000);
        assertThat(price("SKU-1007")).as("still stale").isEqualTo(old);
        dropRedisEntry("SKU-1007");
    }
}
