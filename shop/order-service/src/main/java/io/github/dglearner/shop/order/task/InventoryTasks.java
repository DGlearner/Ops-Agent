package io.github.dglearner.shop.order.task;

import io.github.dglearner.shop.order.client.InventoryClient;
import io.github.dglearner.shop.order.client.InventoryClient.SettleResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends an order's pending inventory command and records the result. Called right after the order settles; a
 * failed attempt stays PENDING with a backoff for the retry job (S5).
 */
@Component
public class InventoryTasks {

    private static final Logger log = LoggerFactory.getLogger(InventoryTasks.class);

    private final InventoryTaskRepository tasks;
    private final InventoryClient inventory;
    private final MeterRegistry registry;

    public InventoryTasks(InventoryTaskRepository tasks, InventoryClient inventory, MeterRegistry registry) {
        this.tasks = tasks;
        this.inventory = inventory;
        this.registry = registry;
    }

    public void execute(String orderId, InventoryAction action) {
        SettleResult result = action == InventoryAction.CONFIRM ? inventory.confirm(orderId) : inventory.release(orderId);
        String outcome = switch (result) {
            case SettleResult.Done done -> {
                tasks.markDone(orderId);
                yield "done";
            }
            case SettleResult.Conflict conflict -> {
                tasks.markFailed(orderId, conflict.code());
                log.atError().addKeyValue("order_id", orderId).addKeyValue("action", action)
                        .addKeyValue("code", conflict.code())
                        .log("Inventory refused the settlement; stock for this order needs manual review");
                yield "conflict";
            }
            case SettleResult.Failed failed -> {
                tasks.scheduleRetry(orderId, failed.failure().describe());
                log.atWarn().addKeyValue("order_id", orderId).addKeyValue("action", action)
                        .addKeyValue("failure", failed.failure().kind())
                        .log("Inventory {} failed, will retry: {}", action.name().toLowerCase(Locale.ROOT),
                                failed.failure().detail());
                yield "retry";
            }
        };
        registry.counter("shop.inventory.tasks", "action", action.name().toLowerCase(Locale.ROOT), "result", outcome)
                .increment();
    }
}
