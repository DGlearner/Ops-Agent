package io.github.dglearner.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The per-instance level in front of Redis: repeat reads stay in the instance, an update drops the copy at once, and
 * an update announced by another instance (on the Redis channel) drops it too. Uses SKU-1003 and SKU-1004.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "shop.flash-sale.consume=false"})
class ProductLocalCacheIT extends ProductCacheTestSupport {

    @Test
    void repeatReadsStayInTheInstanceUntilAnUpdate() throws Exception {
        long before = price("SKU-1003");
        double local = counter("shop.product.reads", "served_by", "local");

        price("SKU-1003");
        price("SKU-1003");
        assertThat(counter("shop.product.reads", "served_by", "local") - local).isEqualTo(2);

        setPrice("SKU-1003", before + 3);
        assertThat(price("SKU-1003")).isEqualTo(before + 3);
    }

    @Test
    void anUpdateAnnouncedByAnotherInstanceDropsTheLocalCopy() throws Exception {
        price("SKU-1004");
        price("SKU-1004");
        double fromRedis = counter("shop.product.reads", "served_by", "redis");

        // Another instance changed SKU-1004 to a version this one has not seen.
        redis.convertAndSend("shop:product:invalidated", "SKU-1004|1000000");

        eventually(() -> {
            price("SKU-1004");
            assertThat(counter("shop.product.reads", "served_by", "redis")).isGreaterThan(fromRedis);
        });
    }
}
