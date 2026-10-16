package io.github.dglearner.shop.order.task;

public record InventoryTask(String orderId, InventoryAction action, String status, int attempts, String lastError) {

    /** Waiting for its first or next attempt. */
    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    /** Inventory refused it (e.g. the reservation was already settled the other way); needs a human. */
    public static final String FAILED = "FAILED";
}
