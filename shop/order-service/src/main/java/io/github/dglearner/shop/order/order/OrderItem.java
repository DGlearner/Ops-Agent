package io.github.dglearner.shop.order.order;

public record OrderItem(String sku, int quantity, long unitPriceCents) {
}
