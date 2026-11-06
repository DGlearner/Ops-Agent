package io.github.dglearner.shop.order.catalog;

import java.time.Instant;

/** A product page's data, served through the cache (§17); {@code version} grows with every change. */
public record ProductDetail(String sku, String name, String description, long priceCents, boolean active,
        long version, Instant updatedAt) {
}
