package io.github.dglearner.shop.order.order;

import static io.github.dglearner.shop.order.order.OrderStatus.CANCELLED;
import static io.github.dglearner.shop.order.order.OrderStatus.CREATED;
import static io.github.dglearner.shop.order.order.OrderStatus.PAID;
import static io.github.dglearner.shop.order.order.OrderStatus.PAYMENT_UNKNOWN;
import static io.github.dglearner.shop.order.order.OrderStatus.STOCK_RESERVED;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.Ids;
import io.github.dglearner.shop.common.LogEvents;
import io.github.dglearner.shop.order.OrderProperties;
import io.github.dglearner.shop.order.catalog.Product;
import io.github.dglearner.shop.order.catalog.ProductRepository;
import io.github.dglearner.shop.order.client.InventoryClient;
import io.github.dglearner.shop.order.client.InventoryClient.ReserveResult;
import io.github.dglearner.shop.order.client.PaymentClient;
import io.github.dglearner.shop.order.client.PaymentClient.ChargeResult;
import io.github.dglearner.shop.order.task.InventoryAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Places orders: the only place that decides an order's outcome (docs/shop-design.md §3, §4). Inventory and payment
 * are called outside any database transaction; each status change is a conditional update.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** The order, and whether it was created by this request or repeats an earlier one. */
    public record Result(Order order, boolean replayed) {
    }

    private final OrderRepository orders;
    private final ProductRepository products;
    private final OrderSettlement settlement;
    private final InventoryClient inventory;
    private final PaymentClient payment;
    private final TransactionTemplate tx;
    private final OrderMetrics metrics;
    private final String currency;

    public OrderService(OrderRepository orders, ProductRepository products, OrderSettlement settlement,
            InventoryClient inventory, PaymentClient payment, TransactionTemplate tx, OrderMetrics metrics,
            OrderProperties properties) {
        this.orders = orders;
        this.products = products;
        this.settlement = settlement;
        this.inventory = inventory;
        this.payment = payment;
        this.tx = tx;
        this.metrics = metrics;
        this.currency = properties.currency();
    }

    public Result create(String idempotencyKey, CreateOrderRequest request) {
        List<OrderLine> lines = normalized(request.items());
        String token = request.paymentTokenOrDefault();
        String hash = RequestHash.of(request.userId(), lines, token);

        Optional<Result> earlier = replay(request.userId(), idempotencyKey, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        List<OrderItem> items = price(lines);
        long amount = items.stream().mapToLong(i -> i.unitPriceCents() * i.quantity()).sum();
        String orderId = Ids.newId();
        try {
            tx.executeWithoutResult(status ->
                    orders.insert(orderId, request.userId(), idempotencyKey, hash, amount, currency, items));
        } catch (DuplicateKeyException e) {
            // An identical request got in first, between the lookup above and this insert.
            return replay(request.userId(), idempotencyKey, hash)
                    .orElseThrow(() -> new IllegalStateException("Duplicate key without an order", e));
        }
        log.atInfo().addKeyValue(LogEvents.ACTION, "order_created")
                .addKeyValue("order_id", orderId).addKeyValue("user_id", request.userId())
                .addKeyValue("amount_cents", amount).addKeyValue("items", describe(lines)).log("Order created");

        Order order = place(orderId, request.userId(), lines, amount, token);
        metrics.placed(order);
        logOutcome(order);
        return new Result(order, false);
    }

    public Optional<Order> find(String orderId) {
        return orders.find(orderId);
    }

    /**
     * Places an order that a flash sale accepted (§16.3): one unit of {@code sku}, with the order id the sale gave the
     * buyer. Delivering the same order again changes nothing and returns it as it is; an unfinished one is completed by
     * background recovery. Empty if the buyer already has a different order of this sale, which only happens if Redis
     * lost track of them; the caller then gives the unit back.
     */
    public Optional<Order> placeFlashSaleOrder(String saleId, String sku, String orderId, String userId,
            String paymentToken) {
        List<OrderLine> lines = List.of(new OrderLine(sku, 1));
        List<OrderItem> items = price(lines);
        long amount = items.getFirst().unitPriceCents();
        try {
            // The order id doubles as the idempotency key; one order per buyer comes from the sale's own constraint.
            tx.executeWithoutResult(status -> orders.insert(orderId, userId, orderId,
                    RequestHash.of(userId, lines, paymentToken), amount, currency, items, saleId));
        } catch (DuplicateKeyException e) {
            Optional<Order> delivered = orders.find(orderId);
            if (delivered.isEmpty()) {
                log.atWarn().addKeyValue(LogEvents.ACTION, "flash_sale_duplicate_buyer")
                        .addKeyValue("order_id", orderId).addKeyValue("user_id", userId)
                        .addKeyValue("flash_sale_id", saleId)
                        .log("Buyer already has an order of this flash sale; the accepted order is dropped");
            }
            return delivered;
        }
        log.atInfo().addKeyValue(LogEvents.ACTION, "order_created")
                .addKeyValue("order_id", orderId).addKeyValue("user_id", userId)
                .addKeyValue("amount_cents", amount).addKeyValue("items", describe(lines))
                .addKeyValue("flash_sale_id", saleId).log("Flash sale order created");

        Order order = place(orderId, userId, lines, amount, paymentToken);
        metrics.placed(order);
        logOutcome(order);
        return Optional.of(order);
    }

    private Order place(String orderId, String userId, List<OrderLine> lines, long amount, String token) {
        switch (inventory.reserve(orderId, lines)) {
            case ReserveResult.Reserved reserved -> {
            }
            case ReserveResult.OutOfStock outOfStock -> {
                // Inventory rolled back the whole reservation: nothing to release.
                return settlement.cancel(orderId, CREATED, FailureReason.OUT_OF_STOCK);
            }
            case ReserveResult.Failed failed -> {
                log.atWarn().addKeyValue(LogEvents.ACTION, "dependency_call_failed")
                        .addKeyValue("dependency", "inventory-service")
                        .addKeyValue("operation", "reserve").addKeyValue("order_id", orderId)
                        .addKeyValue("failure", failed.failure().kind())
                        .log("Inventory reservation failed: {}", failed.failure().detail());
                // Even after a timeout the reservation may exist, or still arrive: release it (§7.3).
                return settle(orderId, CREATED, CANCELLED, FailureReason.INVENTORY_UNAVAILABLE, InventoryAction.RELEASE);
            }
        }
        if (!orders.transition(orderId, CREATED, STOCK_RESERVED, null)) {
            return current(orderId);
        }

        return switch (payment.charge(orderId, userId, amount, currency, token)) {
            case ChargeResult.Succeeded succeeded ->
                    settle(orderId, STOCK_RESERVED, PAID, null, InventoryAction.CONFIRM);
            case ChargeResult.Declined declined ->
                    settle(orderId, STOCK_RESERVED, CANCELLED, FailureReason.PAYMENT_DECLINED, InventoryAction.RELEASE);
            case ChargeResult.NotCharged notCharged -> {
                log.atWarn().addKeyValue(LogEvents.ACTION, "dependency_call_failed")
                        .addKeyValue("dependency", "payment-service")
                        .addKeyValue("operation", "charge").addKeyValue("order_id", orderId)
                        .addKeyValue("failure", notCharged.failure().kind())
                        .log("Charge request did not reach payment: {}", notCharged.failure().detail());
                yield settle(orderId, STOCK_RESERVED, CANCELLED, FailureReason.PAYMENT_UNAVAILABLE,
                        InventoryAction.RELEASE);
            }
            case ChargeResult.Unknown unknown -> {
                // A timeout is not a failure: the charge may have gone through. Keep the stock and reconcile.
                log.atWarn().addKeyValue(LogEvents.ACTION, "dependency_call_failed")
                        .addKeyValue("dependency", "payment-service")
                        .addKeyValue("operation", "charge").addKeyValue("order_id", orderId)
                        .addKeyValue("failure", unknown.failure().kind())
                        .log("Charge outcome unknown: {}", unknown.failure().detail());
                orders.transition(orderId, STOCK_RESERVED, PAYMENT_UNKNOWN, null);
                yield current(orderId);
            }
        };
    }

    private Order settle(String orderId, OrderStatus from, OrderStatus to, FailureReason reason,
            InventoryAction action) {
        return settlement.settle(orderId, from, to, reason, action);
    }

    private Optional<Result> replay(String userId, String idempotencyKey, String hash) {
        return orders.findByKey(userId, idempotencyKey).map(stored -> {
            Order order = stored.order();
            if (!stored.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different order request")
                        .with("order_id", order.orderId());
            }
            if (!order.status().isFinal()) {
                throw new ApiException(HttpStatus.CONFLICT, "ORDER_IN_PROGRESS",
                        "The original request for this Idempotency-Key is still being processed")
                        .with("order_id", order.orderId()).with("status", order.status());
            }
            metrics.replayed();
            log.atInfo().addKeyValue(LogEvents.ACTION, "order_replayed").addKeyValue("order_id", order.orderId())
                    .log("Order request replayed");
            return new Result(order, true);
        });
    }

    private List<OrderItem> price(List<OrderLine> lines) {
        Map<String, Product> catalog = products.findActive(lines.stream().map(OrderLine::sku).toList());
        List<OrderItem> items = new ArrayList<>();
        for (OrderLine line : lines) {
            Product product = catalog.get(line.sku());
            if (product == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU",
                        "No product " + line.sku()).with("sku", line.sku());
            }
            items.add(new OrderItem(line.sku(), line.quantity(), product.priceCents()));
        }
        return items;
    }

    private Order current(String orderId) {
        return orders.find(orderId).orElseThrow();
    }

    private static void logOutcome(Order order) {
        var event = order.status() == CANCELLED && order.failureReason().dependencyFailure()
                ? log.atWarn() : log.atInfo();
        event = event.addKeyValue(LogEvents.ACTION, switch (order.status()) {
                    case PAID -> "order_paid";
                    case CANCELLED -> "order_cancelled";
                    case PAYMENT_UNKNOWN -> "order_payment_unknown";
                    default -> "order_" + order.status().name().toLowerCase(Locale.ROOT);
                })
                .addKeyValue("order_id", order.orderId()).addKeyValue("status", order.status())
                .addKeyValue("amount_cents", order.amountCents());
        if (order.failureReason() != null) {
            event = event.addKeyValue("failure_reason", order.failureReason());
        }
        event.log(switch (order.status()) {
                    case PAID -> "Order paid";
                    case CANCELLED -> "Order cancelled";
                    case PAYMENT_UNKNOWN -> "Order awaits payment reconciliation";
                    default -> "Order left in " + order.status();
                });
    }

    /** Sorted by SKU, which inventory also uses as its lock order; a SKU listed twice is rejected. */
    private static List<OrderLine> normalized(List<OrderLine> items) {
        List<OrderLine> sorted = items.stream().sorted(Comparator.comparing(OrderLine::sku)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).sku().equals(sorted.get(i - 1).sku())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SKU",
                        "SKU " + sorted.get(i).sku() + " is listed more than once").with("sku", sorted.get(i).sku());
            }
        }
        return sorted;
    }

    private static String describe(List<OrderLine> lines) {
        return lines.stream().map(l -> l.sku() + "x" + l.quantity()).collect(Collectors.joining(","));
    }
}
