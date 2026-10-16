package io.github.dglearner.shop.common.faults;

import java.time.Duration;
import java.time.Instant;

/**
 * One injected fault: requests matching {@code path} (a Spring path pattern such as {@code /api/v1/payments} or
 * {@code /api/v1/reservations/**}) and, if set, {@code method} are delayed by {@code delay} and fail with
 * {@code errorStatus} at {@code errorRate}. {@link Phase#BEFORE} acts before the request is processed,
 * {@link Phase#AFTER} after it was processed but before the response leaves, i.e. a lost or late answer.
 */
public record FaultRule(String id, String path, String method, Phase phase, Duration delay, double errorRate,
        int errorStatus, Instant createdAt) {

    public enum Phase {
        BEFORE,
        AFTER
    }
}
