package io.github.dglearner.shop.order.order;

import static io.github.dglearner.shop.order.order.OrderStatus.CANCELLED;
import static io.github.dglearner.shop.order.order.OrderStatus.CREATED;
import static io.github.dglearner.shop.order.order.OrderStatus.PAID;
import static io.github.dglearner.shop.order.order.OrderStatus.PAYMENT_UNKNOWN;
import static io.github.dglearner.shop.order.order.OrderStatus.STOCK_RESERVED;

import io.github.dglearner.shop.order.OrderProperties;
import io.github.dglearner.shop.order.client.PaymentClient;
import io.github.dglearner.shop.order.client.PaymentClient.VoidResult;
import io.github.dglearner.shop.order.task.InventoryAction;
import io.github.dglearner.shop.order.task.InventoryTask;
import io.github.dglearner.shop.order.task.InventoryTaskRepository;
import io.github.dglearner.shop.order.task.InventoryTasks;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs that bring every order to a final state and every stock reservation to a settlement, whatever
 * failed on the way (docs/shop-design.md §7.4–7.5, §10). Each step uses conditional updates, so it is safe next to
 * live requests and on more than one instance.
 */
@Component
@ConditionalOnBooleanProperty(name = "shop.order.recovery.enabled", matchIfMissing = true)
public class OrderRecovery {

    private static final Logger log = LoggerFactory.getLogger(OrderRecovery.class);
    private static final int BATCH = 50;

    private final OrderRepository orders;
    private final OrderSettlement settlement;
    private final PaymentClient payment;
    private final InventoryTaskRepository taskRepository;
    private final InventoryTasks tasks;
    private final OrderProperties.Recovery properties;
    private final MeterRegistry registry;

    public OrderRecovery(OrderRepository orders, OrderSettlement settlement, PaymentClient payment,
            InventoryTaskRepository taskRepository, InventoryTasks tasks, OrderProperties properties,
            MeterRegistry registry) {
        this.orders = orders;
        this.settlement = settlement;
        this.payment = payment;
        this.taskRepository = taskRepository;
        this.tasks = tasks;
        this.properties = properties.recovery();
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${shop.order.recovery.interval}", initialDelayString = "${shop.order.recovery.interval}")
    public void run() {
        step("abandoned orders", this::closeAbandonedOrders);
        step("payment reconciliation", this::reconcilePayments);
        step("inventory retries", this::retryInventoryTasks);
    }

    /**
     * An order stuck in CREATED or STOCK_RESERVED lost the request that was placing it. Before the charge (CREATED)
     * it is cancelled and any reservation released; after it (STOCK_RESERVED) the charge may have been sent, so the
     * order joins payment reconciliation.
     */
    void closeAbandonedOrders() {
        for (Map.Entry<String, OrderStatus> order :
                orders.findUnchangedFor(properties.abandonAfter(), BATCH, CREATED, STOCK_RESERVED)) {
            String orderId = order.getKey();
            if (order.getValue() == CREATED) {
                Order closed = settlement.settle(orderId, CREATED, CANCELLED, FailureReason.ABANDONED,
                        InventoryAction.RELEASE);
                recovered("abandoned", "cancelled", closed);
            } else if (orders.transition(orderId, STOCK_RESERVED, PAYMENT_UNKNOWN, null)) {
                recovered("abandoned", "payment_unknown", orders.find(orderId).orElseThrow());
            }
        }
    }

    /**
     * Settles orders whose charge outcome is unknown. Voiding the payment answers how the charge ended and, if it
     * never happened, makes sure it cannot happen later; only then is the order cancelled.
     */
    void reconcilePayments() {
        for (Map.Entry<String, OrderStatus> order :
                orders.findUnchangedFor(properties.reconcileAfter(), BATCH, PAYMENT_UNKNOWN)) {
            String orderId = order.getKey();
            switch (payment.voidUnlessCharged(orderId)) {
                case VoidResult.Charged charged -> recovered("reconcile", "paid",
                        settlement.settle(orderId, PAYMENT_UNKNOWN, PAID, null, InventoryAction.CONFIRM));
                case VoidResult.Declined declined -> recovered("reconcile", "declined",
                        settlement.settle(orderId, PAYMENT_UNKNOWN, CANCELLED, FailureReason.PAYMENT_DECLINED,
                                InventoryAction.RELEASE));
                case VoidResult.Voided voided -> recovered("reconcile", "voided",
                        settlement.settle(orderId, PAYMENT_UNKNOWN, CANCELLED, FailureReason.PAYMENT_UNAVAILABLE,
                                InventoryAction.RELEASE));
                case VoidResult.Processing processing -> {
                    orders.touch(orderId, PAYMENT_UNKNOWN);
                    count("reconcile", "still_processing");
                }
                case VoidResult.Failed failed -> {
                    orders.touch(orderId, PAYMENT_UNKNOWN);
                    count("reconcile", "payment_unreachable");
                    log.atWarn().addKeyValue("order_id", orderId).addKeyValue("failure", failed.failure().kind())
                            .log("Payment reconciliation failed, will retry: {}", failed.failure().detail());
                }
            }
        }
    }

    /** Re-sends confirm or release commands that inventory has not acknowledged yet. */
    void retryInventoryTasks() {
        for (InventoryTask task : taskRepository.findDue(BATCH)) {
            if (taskRepository.claim(task.orderId())) {
                tasks.execute(task.orderId(), task.action());
            }
        }
    }

    private void recovered(String action, String result, Order order) {
        count(action, result);
        var event = log.atInfo().addKeyValue("order_id", order.orderId()).addKeyValue("status", order.status());
        if (order.failureReason() != null) {
            event = event.addKeyValue("failure_reason", order.failureReason());
        }
        event.log(action.equals("reconcile") ? "Payment reconciled: order {}" : "Abandoned order closed: {}",
                order.status());
    }

    private void count(String action, String result) {
        registry.counter("shop.order.recovery", "action", action, "result", result).increment();
    }

    private static void step(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Recovery step '{}' failed: {}", name, e.getMessage());
        }
    }
}
