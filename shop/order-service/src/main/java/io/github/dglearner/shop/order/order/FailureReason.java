package io.github.dglearner.shop.order.order;

/** Why an order was cancelled. */
public enum FailureReason {
    OUT_OF_STOCK(false),
    PAYMENT_DECLINED(false),
    /** Inventory could not be reached, timed out or failed; any reservation it may have made is released. */
    INVENTORY_UNAVAILABLE(true),
    /** The charge request never reached the payment service, so no money was taken. */
    PAYMENT_UNAVAILABLE(true),
    /** The request placing the order stopped before reserving finished, e.g. the service crashed. */
    ABANDONED(false);

    private final boolean dependencyFailure;

    FailureReason(boolean dependencyFailure) {
        this.dependencyFailure = dependencyFailure;
    }

    /** True for infrastructure failures, which the API reports as 503, as opposed to business outcomes. */
    public boolean dependencyFailure() {
        return dependencyFailure;
    }
}
