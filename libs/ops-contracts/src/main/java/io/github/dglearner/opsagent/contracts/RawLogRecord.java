package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One raw log line accepted by ingest-service. Topic {@link Topics#LOGS_RAW}, key = {@code host}.
 *
 * @param eventId    UUIDv7 assigned at ingest; stays the same through parsing
 * @param requestId  shared by all lines of one HTTP request
 * @param sourceId   log source resolved from the API key
 * @param service    may be null when the shipper does not know it
 * @param sourceName file path or stream name, may be null
 */
public record RawLogRecord(
        UUID eventId,
        UUID requestId,
        String sourceId,
        SourceType sourceType,
        String host,
        String service,
        String sourceName,
        String message,
        Instant receivedAt) {

    public RawLogRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
