package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application logs, in either of two forms:
 * <ul>
 * <li>{@code <ISO time> <LEVEL> key=value ...}. v1 expected the service name before the level and therefore never
 * matched these lines.</li>
 * <li>One ECS JSON object per line, as Spring Boot writes it (the shop's services). The event type is the line's
 * {@code event.action}, a stable name the application gives each event (docs/shop-design.md §8.1); lines without one
 * are typed by level. The message is the line's {@code message}; the other fields are kept flattened
 * ({@code trace.id}, {@code order_id}, {@code error.type}, ...), except process details and stack traces.</li>
 * </ul>
 */
public class AppServiceParser implements LogParser {

    private static final Pattern LINE = Pattern.compile(
            "^(?<time>\\d{4}-\\d{2}-\\d{2}T\\S+)\\s+(?<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|CRITICAL)\\s+"
                    + "(?<rest>.*)$");

    @Override
    public SourceType sourceType() {
        return SourceType.APP_SERVICE;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        if (raw.message().startsWith("{")) {
            return parseJson(raw.message());
        }
        Matcher m = LINE.matcher(raw.message());
        if (!m.matches()) {
            return Optional.empty();
        }
        String level = m.group("level");
        Map<String, Object> fields = FieldValues.typed(Logfmt.parse(m.group("rest")));
        String error = String.valueOf(fields.getOrDefault("error", "")).toLowerCase(Locale.ROOT);
        String event = String.valueOf(fields.getOrDefault("event", "")).toLowerCase(Locale.ROOT);
        long status = FieldValues.asLong(fields.get("status"), 0);

        ParsedLog.Builder parsed = ParsedLog.event("application", "app_log", levelSeverity(level));
        if (error.contains("connection refused") && (fields.containsKey("db_host") || event.startsWith("db_"))) {
            parsed.type("app_db_connection_error", Severity.MEDIUM).tags("application", "database", "availability");
        } else if (status >= 500) {
            parsed.type("app_http_server_error", Severity.MEDIUM).tags("application", "http");
        } else if (event.equals("login_failed") || m.group("rest").toLowerCase(Locale.ROOT).contains("login failed")) {
            parsed.type("app_login_failed", Severity.LOW).tags("application", "auth");
        } else if (levelSeverity(level).isAtLeast(Severity.MEDIUM)) {
            parsed.type("app_error", Severity.MEDIUM).tags("application");
        } else if (level.startsWith("WARN")) {
            parsed.type("app_warning", Severity.LOW).tags("application");
        } else {
            parsed.tags("application");
        }
        Object service = fields.get("service");
        return Optional.of(parsed
                .timestamp(Timestamps.iso(m.group("time")))
                .service(service == null ? null : service.toString())
                .field("level", level)
                .fields(fields)
                .build());
    }

    private static Optional<ParsedLog> parseJson(String line) {
        Optional<Map<String, Object>> parsed = EcsJson.parse(line);
        if (parsed.isEmpty() || !(parsed.get().get("@timestamp") instanceof String time)) {
            return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>(parsed.get());
        String level = String.valueOf(fields.getOrDefault("log.level", "INFO")).toUpperCase(Locale.ROOT);
        String logger = text(fields.get("log.logger"));
        String service = text(fields.get("service.name"));
        String message = text(fields.get("message"));
        String action = text(fields.get("event.action"));
        fields.keySet().removeIf(name -> name.equals("@timestamp") || name.equals("message") || name.startsWith("log.")
                || name.startsWith("service.") || name.startsWith("process.") || name.startsWith("ecs.")
                || name.equals("event.action") || name.equals("error.stack_trace"));

        Severity severity = levelSeverity(level);
        String eventType = action != null ? action
                : severity.isAtLeast(Severity.MEDIUM) ? "app_error"
                : level.startsWith("WARN") ? "app_warning" : "app_log";
        String component = logger == null ? "application" : logger.substring(logger.lastIndexOf('.') + 1);
        return Optional.of(ParsedLog.event(component, eventType, severity)
                .tags("application")
                .timestamp(Timestamps.iso(time))
                .service(service)
                .message(message)
                .field("level", level)
                .field("logger", logger)
                .fields(fields)
                .build());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Severity levelSeverity(String level) {
        return switch (level) {
            case "ERROR", "FATAL", "CRITICAL" -> Severity.MEDIUM;
            case "WARN", "WARNING" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }
}
