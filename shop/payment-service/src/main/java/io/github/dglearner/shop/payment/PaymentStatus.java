package io.github.dglearner.shop.payment;

public enum PaymentStatus {
    /** Claimed for the order; the provider has not answered yet. */
    PROCESSING,
    SUCCEEDED,
    DECLINED
}
