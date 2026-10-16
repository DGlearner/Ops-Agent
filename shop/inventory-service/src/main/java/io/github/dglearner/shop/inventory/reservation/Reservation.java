package io.github.dglearner.shop.inventory.reservation;

import java.time.Instant;
import java.util.List;

/** Reservation of one order; {@code items} is sorted by SKU and empty for a release tombstone. */
public record Reservation(String orderId, ReservationStatus status, List<LineItem> items, Instant createdAt,
        Instant updatedAt) {
}
