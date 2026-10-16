package io.github.dglearner.shop.order.order;

import java.time.Instant;
import java.util.List;

public record Order(String orderId, String userId, OrderStatus status, FailureReason failureReason, long amountCents,
        String currency, List<OrderItem> items, Instant createdAt, Instant updatedAt) {
}
