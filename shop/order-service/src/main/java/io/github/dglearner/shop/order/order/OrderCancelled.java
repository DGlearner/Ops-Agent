package io.github.dglearner.shop.order.order;

/**
 * Published inside the transaction that cancels an order, whatever cancelled it (placement or background recovery).
 * A listener can record follow-up work in that same transaction, e.g. giving a flash sale unit back (§16.4).
 */
public record OrderCancelled(String orderId) {
}
