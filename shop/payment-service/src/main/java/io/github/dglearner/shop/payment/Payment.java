package io.github.dglearner.shop.payment;

import java.time.Instant;

/** A payment as returned to clients; the payment token is never echoed back. */
public record Payment(String paymentId, String orderId, String userId, long amountCents, String currency,
        PaymentStatus status, String declineReason, Instant createdAt, Instant updatedAt) {
}
