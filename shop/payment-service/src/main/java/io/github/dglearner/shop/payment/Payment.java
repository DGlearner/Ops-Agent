package io.github.dglearner.shop.payment;

import java.time.Instant;

/**
 * A payment as returned to clients; the payment token is never echoed back. A VOIDED payment has no user, amount or
 * currency.
 */
public record Payment(String paymentId, String orderId, String userId, Long amountCents, String currency,
        PaymentStatus status, String declineReason, Instant createdAt, Instant updatedAt) {
}
