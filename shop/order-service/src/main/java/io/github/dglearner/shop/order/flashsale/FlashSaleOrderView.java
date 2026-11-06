package io.github.dglearner.shop.order.flashsale;

import io.github.dglearner.shop.order.order.FailureReason;
import io.github.dglearner.shop.order.order.Order;

/**
 * A flash sale order as the buyer sees it. {@code status} is QUEUED while the order waits in the queue (it has no row
 * yet), then the order's own status (§4.2).
 */
public record FlashSaleOrderView(String orderId, String saleId, String userId, String status,
        FailureReason failureReason) {

    static final String QUEUED = "QUEUED";

    static FlashSaleOrderView queued(String orderId, String saleId, String userId) {
        return new FlashSaleOrderView(orderId, saleId, userId, QUEUED, null);
    }

    static FlashSaleOrderView of(Order order) {
        return new FlashSaleOrderView(order.orderId(), order.flashSaleId(), order.userId(), order.status().name(),
                order.failureReason());
    }
}
