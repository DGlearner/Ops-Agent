package io.github.dglearner.shop.inventory.reservation;

public enum ReservationStatus {
    /** Units moved from available to reserved, waiting for the order's outcome. */
    RESERVED,
    /** The order was paid: reserved units became sold. */
    CONFIRMED,
    /** The order was cancelled: reserved units went back to available, or nothing was ever reserved (tombstone). */
    RELEASED
}
