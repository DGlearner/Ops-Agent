package io.github.dglearner.shop.inventory.reservation;

import static io.github.dglearner.shop.inventory.reservation.ReservationStatus.CONFIRMED;
import static io.github.dglearner.shop.inventory.reservation.ReservationStatus.RELEASED;
import static io.github.dglearner.shop.inventory.reservation.ReservationStatus.RESERVED;

import io.github.dglearner.shop.common.ApiException;
import io.github.dglearner.shop.common.DeadlockRetry;
import io.github.dglearner.shop.inventory.InventoryMetrics;
import io.github.dglearner.shop.inventory.stock.StockRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reserve, confirm and release, each idempotent per order id (docs/shop-design.md §7.2–7.3).
 *
 * <p>Every transaction locks the reservation row first and then stock rows in SKU order, so two orders can never
 * wait on each other's rows in opposite order.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservations;
    private final StockRepository stock;
    private final TransactionTemplate tx;
    private final InventoryMetrics metrics;

    public ReservationService(ReservationRepository reservations, StockRepository stock, TransactionTemplate tx,
            InventoryMetrics metrics) {
        this.reservations = reservations;
        this.stock = stock;
        this.tx = tx;
        this.metrics = metrics;
    }

    /** The reservation after a command, and whether this request changed it ({@code false} for a replay). */
    public record Outcome(Reservation reservation, boolean changed) {
    }

    /** What a committed transaction did; metrics and logs are written only after commit. */
    private record Result(Reservation reservation, String result) {

        Outcome outcome() {
            return new Outcome(reservation, !result.equals(REPLAYED));
        }
    }

    private static final String REPLAYED = "replayed";

    public Outcome reserve(String orderId, List<LineItem> requested) {
        List<LineItem> items = normalized(requested);
        try {
            Reservation reservation = DeadlockRetry.run("reserve", () -> tx.execute(status -> {
                // The primary key claims the order id; a concurrent duplicate waits here until this commits.
                reservations.insert(orderId, RESERVED);
                for (LineItem item : items) {
                    if (!stock.reserve(item.sku(), item.quantity())) {
                        throw shortage(item);
                    }
                }
                reservations.insertItems(orderId, items);
                return reservations.find(orderId).orElseThrow();
            }));
            metrics.operation("reserve", "reserved");
            log.atInfo().addKeyValue("order_id", orderId).addKeyValue("items", describe(items))
                    .log("Stock reserved");
            return new Outcome(reservation, true);
        } catch (DuplicateKeyException e) {
            return replayReserve(orderId, items);
        } catch (ApiException e) {
            metrics.operation("reserve", e.code().toLowerCase(Locale.ROOT));
            log.atInfo().addKeyValue("order_id", orderId).addKeyValue("code", e.code())
                    .addKeyValue("sku", e.properties().get("sku")).log("Reservation rejected: {}", e.getMessage());
            throw e;
        }
    }

    private Outcome replayReserve(String orderId, List<LineItem> items) {
        Reservation existing = reservations.find(orderId)
                .orElseThrow(() -> new IllegalStateException("Duplicate key without a row for " + orderId));
        if (existing.status() == RELEASED) {
            // The order was cancelled before this request arrived: reserving now would hold stock forever.
            metrics.operation("reserve", "late_after_release");
            log.atWarn().addKeyValue("order_id", orderId).log("Late reservation rejected: order already released");
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_RELEASED",
                    "Reservation for order " + orderId + " was already released").with("order_id", orderId);
        }
        if (!existing.items().equals(items)) {
            metrics.operation("reserve", "reservation_mismatch");
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RESERVATION_MISMATCH",
                    "Order " + orderId + " is already reserved with different items").with("order_id", orderId);
        }
        metrics.replay("reserve");
        log.atInfo().addKeyValue("order_id", orderId).log("Reservation request replayed");
        return new Outcome(existing, false);
    }

    public Outcome confirm(String orderId) {
        Result result = DeadlockRetry.run("confirm", () -> tx.execute(status -> {
            ReservationStatus current = reservations.lockStatus(orderId).orElseThrow(() -> notFound(orderId));
            return switch (current) {
                case RESERVED -> {
                    reservations.items(orderId).forEach(i -> stock.confirm(i.sku(), i.quantity()));
                    reservations.updateStatus(orderId, CONFIRMED);
                    yield new Result(reservations.find(orderId).orElseThrow(), "confirmed");
                }
                case CONFIRMED -> new Result(reservations.find(orderId).orElseThrow(), REPLAYED);
                case RELEASED -> throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_RELEASED",
                        "Reservation for order " + orderId + " was released and cannot be confirmed")
                        .with("order_id", orderId);
            };
        }));
        return record("confirm", result);
    }

    public Outcome release(String orderId) {
        Result result = DeadlockRetry.run("release", () -> tx.execute(status -> {
            Optional<ReservationStatus> current = reservations.lockStatus(orderId);
            if (current.isEmpty()) {
                // Release before reserve, e.g. the reserve request timed out and is still in flight. The tombstone
                // makes that request fail when it arrives (§7.3).
                reservations.insert(orderId, RELEASED);
                return new Result(reservations.find(orderId).orElseThrow(), "tombstone");
            }
            return switch (current.get()) {
                case RESERVED -> {
                    reservations.items(orderId).forEach(i -> stock.release(i.sku(), i.quantity()));
                    reservations.updateStatus(orderId, RELEASED);
                    yield new Result(reservations.find(orderId).orElseThrow(), "released");
                }
                case RELEASED -> new Result(reservations.find(orderId).orElseThrow(), REPLAYED);
                case CONFIRMED -> throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_CONFIRMED",
                        "Reservation for order " + orderId + " was confirmed and cannot be released")
                        .with("order_id", orderId);
            };
        }));
        return record("release", result);
    }

    public Optional<Reservation> find(String orderId) {
        return reservations.find(orderId);
    }

    private Outcome record(String operation, Result result) {
        String orderId = result.reservation().orderId();
        if (result.result().equals(REPLAYED)) {
            metrics.replay(operation);
            log.atInfo().addKeyValue("order_id", orderId).addKeyValue("operation", operation)
                    .log("Reservation request replayed");
        } else {
            metrics.operation(operation, result.result());
            log.atInfo().addKeyValue("order_id", orderId).addKeyValue("operation", operation)
                    .addKeyValue("result", result.result())
                    .addKeyValue("items", describe(result.reservation().items()))
                    .log("Reservation {}", result.result().equals("tombstone")
                            ? "released before it was made (tombstone)" : result.result());
        }
        return result.outcome();
    }

    private ApiException shortage(LineItem item) {
        return stock.find(item.sku())
                .map(s -> new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                        "Not enough stock for " + item.sku())
                        .with("sku", item.sku()).with("requested", item.quantity()).with("available", s.available()))
                .orElseGet(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU",
                        "Unknown SKU " + item.sku()).with("sku", item.sku()));
    }

    private static ApiException notFound(String orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
                "No reservation for order " + orderId).with("order_id", orderId);
    }

    /** Sorted by SKU, the lock order; a SKU listed twice is rejected rather than silently merged. */
    private static List<LineItem> normalized(List<LineItem> items) {
        List<LineItem> sorted = items.stream().sorted(Comparator.comparing(LineItem::sku)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).sku().equals(sorted.get(i - 1).sku())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SKU",
                        "SKU " + sorted.get(i).sku() + " is listed more than once").with("sku", sorted.get(i).sku());
            }
        }
        return sorted;
    }

    private static String describe(List<LineItem> items) {
        return items.stream().map(i -> i.sku() + "x" + i.quantity()).collect(Collectors.joining(","));
    }
}
