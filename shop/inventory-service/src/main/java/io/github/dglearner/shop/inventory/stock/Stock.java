package io.github.dglearner.shop.inventory.stock;

import java.time.Instant;

/** Stock ledger of one SKU; {@code available + reserved + sold == total} always holds. */
public record Stock(String sku, int total, int available, int reserved, int sold, Instant updatedAt) {
}
