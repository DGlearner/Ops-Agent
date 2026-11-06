package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;

/**
 * How many lines share a value (an event type, a severity, a service, a host) in a time range: when the first and
 * the last were, the most severe of them, and the newest one's message as an example.
 */
public record LogCount(String key, long count, Severity severity, Instant firstAt, Instant lastAt, String example) {
}
