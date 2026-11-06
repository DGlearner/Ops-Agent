package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One stored log line, as the API returns it; {@code timestamp} is the line's own time. */
public record LogEventView(UUID eventId, Instant timestamp, Instant receivedAt, String service, String host,
        String sourceType, String eventType, Severity severity, String traceId, String message,
        Map<String, Object> fields) {
}
