package io.github.dglearner.shop.common;

/**
 * The field that names what a log line records, e.g. {@code order_cancelled} or {@code dependency_call_failed}: ECS's
 * {@code event.action}. The names are stable, unlike messages, so detection rules match them instead of message text;
 * the list is in docs/shop-design.md §8.1. Spring Boot's ECS format writes it nested, as
 * {@code "event":{"action":"..."}}.
 */
public final class LogEvents {

    public static final String ACTION = "event.action";

    private LogEvents() {
    }
}
