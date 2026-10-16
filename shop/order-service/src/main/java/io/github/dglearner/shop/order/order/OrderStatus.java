package io.github.dglearner.shop.order.order;

/** See docs/shop-design.md §4.2. */
public enum OrderStatus {
    CREATED,
    STOCK_RESERVED,
    /** The charge timed out or failed in a way that does not tell whether money was taken; awaits reconciliation. */
    PAYMENT_UNKNOWN,
    PAID,
    CANCELLED;

    public boolean isFinal() {
        return this == PAID || this == CANCELLED;
    }
}
