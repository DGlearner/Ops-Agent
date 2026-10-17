package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a parser extracted from one line. {@link ParserRegistry} combines it with the raw record into a
 * {@code NormalizedEvent}.
 *
 * @param timestamp null when the line has no usable time; the receive time is used instead
 * @param service   null to keep the service declared by the log shipper
 * @param message   the human-readable text, e.g. the {@code message} of a JSON log line; null to keep the whole line
 */
public record ParsedLog(
        String component,
        String eventType,
        Severity severity,
        Instant timestamp,
        String service,
        Map<String, Object> fields,
        List<String> tags,
        String message) {

    public static Builder event(String component, String eventType, Severity severity) {
        return new Builder(component, eventType, severity);
    }

    public static final class Builder {

        private final String component;
        private String eventType;
        private Severity severity;
        private Instant timestamp;
        private String service;
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private List<String> tags = List.of();
        private String message;

        private Builder(String component, String eventType, Severity severity) {
            this.component = component;
            this.eventType = eventType;
            this.severity = severity;
        }

        public Builder type(String eventType, Severity severity) {
            this.eventType = eventType;
            this.severity = severity;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        /** Null values are skipped, so optional captures can be passed directly. */
        public Builder field(String name, Object value) {
            if (value != null) {
                fields.put(name, value);
            }
            return this;
        }

        public Builder fields(Map<String, ?> values) {
            values.forEach(this::field);
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder tags(String... tags) {
            this.tags = List.of(tags);
            return this;
        }

        public ParsedLog build() {
            return new ParsedLog(component, eventType, severity, timestamp, service, fields, tags, message);
        }
    }
}
