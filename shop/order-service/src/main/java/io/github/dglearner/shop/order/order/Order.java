package io.github.dglearner.shop.order.order;

import java.time.Instant;
import java.util.List;

/** {@code flashSaleId} is the flash sale the order was placed through (§16), null for a regular order. */
public record Order(String orderId, String userId, OrderStatus status, FailureReason failureReason, long amountCents,
        String currency, List<OrderItem> items, Instant createdAt, Instant updatedAt, String flashSaleId) {
}
