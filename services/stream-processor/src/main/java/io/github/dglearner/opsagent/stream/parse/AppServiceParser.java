package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application logs in {@code <ISO time> <LEVEL> key=value ...} form. v1 expected the service name before the level
 * and therefore never matched these lines.
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

    private static Severity levelSeverity(String level) {
        return switch (level) {
            case "ERROR", "FATAL", "CRITICAL" -> Severity.MEDIUM;
            case "WARN", "WARNING" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }
}
