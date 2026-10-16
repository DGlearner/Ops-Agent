package io.github.dglearner.shop.order.order;

import io.github.dglearner.shop.order.task.InventoryAction;
import io.github.dglearner.shop.order.task.InventoryTaskRepository;
import io.github.dglearner.shop.order.task.InventoryTasks;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Final status changes, shared by order placement and background recovery. */
@Component
public class OrderSettlement {

    private final OrderRepository orders;
    private final InventoryTaskRepository taskRepository;
    private final InventoryTasks tasks;
    private final TransactionTemplate tx;

    public OrderSettlement(OrderRepository orders, InventoryTaskRepository taskRepository, InventoryTasks tasks,
            TransactionTemplate tx) {
        this.orders = orders;
        this.taskRepository = taskRepository;
        this.tasks = tasks;
        this.tx = tx;
    }

    /**
     * Moves the order to its final status and records the inventory command in the same transaction, then sends the
     * command. If sending fails the command stays pending and is retried, so stock is never left reserved. Does
     * nothing if the order is no longer in {@code from}. Returns the order as it now is.
     */
    public Order settle(String orderId, OrderStatus from, OrderStatus to, FailureReason reason,
            InventoryAction action) {
        boolean moved = Boolean.TRUE.equals(tx.execute(status -> {
            if (!orders.transition(orderId, from, to, reason)) {
                return false;
            }
            taskRepository.insert(orderId, action);
            return true;
        }));
        if (moved) {
            tasks.execute(orderId, action);
        }
        return orders.find(orderId).orElseThrow();
    }
}
