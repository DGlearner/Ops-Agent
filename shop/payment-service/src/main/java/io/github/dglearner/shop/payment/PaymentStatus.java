package io.github.dglearner.shop.payment;

public enum PaymentStatus {
    /** Claimed for the order; the provider has not answered yet. */
    PROCESSING,
    SUCCEEDED,
    DECLINED,
    /** No charge arrived before reconciliation gave up on it; a later charge for the order is refused. */
    VOIDED
}
