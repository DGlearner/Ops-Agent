package io.github.dglearner.shop.order.task;

/** The inventory command that settles an order. */
public enum InventoryAction {
    /** The order was paid: reserved stock becomes sold. */
    CONFIRM,
    /** The order was cancelled after stock may have been reserved: give it back. */
    RELEASE
}
