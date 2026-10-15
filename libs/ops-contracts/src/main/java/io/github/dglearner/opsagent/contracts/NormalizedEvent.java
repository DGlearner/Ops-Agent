package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A parsed and redacted log line. Topic {@link Topics#LOGS_NORMALIZED}, key = {@code service}, or {@code host}
 * when the service is unknown.
 *
 * @param timestamp event time taken from the log line; {@code receivedAt} when the line has no usable time
 * @param message   the original line after redaction
 * @param fields    values extracted by the parser: strings, numbers or booleans, never null
 */
public record NormalizedEvent(
        UUID eventId,
        String sourceId,
        SourceType sourceType,
        String sourceName,
        String host,
        String service,
        String component,
        String eventType,
        Severity severity,
        Instant timestamp,
        Instant receivedAt,
        String message,
        Map<String, Object> fields,
        List<String> tags) {

    public NormalizedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(message, "message");
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** The service if known, otherwise the host. Used as Kafka key and default correlation group. */
    public String serviceOrHost() {
        return service != null ? service : host;
    }
}
