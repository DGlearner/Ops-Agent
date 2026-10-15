package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL server, health-check and backup messages, usually inside a syslog envelope. Lines without one are still
 * classified by content, because availability errors matter more than their format.
 */
public class MysqlParser implements LogParser {

    private static final Pattern LEVEL = Pattern.compile("^\\[(?<level>[A-Za-z]+)]\\s*(?<message>.*)$");
    private static final Pattern ABORTED = Pattern.compile(
            "Aborted connection (?<id>\\d+) to db: '(?<db>[^']*)' user: '(?<user>[^']*)' host: '(?<host>[^']*)'");
    private static final Pattern TARGET = Pattern.compile("mysql://(?<target>\\S+)");

    /** Queries at or above this latency count as slow. */
    private static final double SLOW_SECONDS = 1.0;

    @Override
    public SourceType sourceType() {
        return SourceType.MYSQL;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        Optional<SyslogLine> syslog = SyslogLine.parse(raw.message(), raw.receivedAt());
        String body = syslog.map(SyslogLine::body).orElse(raw.message());
        Instant timestamp = syslog.map(SyslogLine::timestamp).orElse(null);

        ParsedLog.Builder classified = classify(body);
        if (classified == null && syslog.isEmpty()) {
            return Optional.empty();
        }
        ParsedLog.Builder event = classified != null ? classified : ParsedLog.event("mysql", "mysql_log", Severity.INFO);
        syslog.ifPresent(line -> event.field("program", line.program()).field("pid", line.pid()));
        return Optional.of(event.timestamp(timestamp).build());
    }

    /** Null when nothing specific matched. */
    private static ParsedLog.Builder classify(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused") || lower.contains("can't connect")) {
            Matcher target = TARGET.matcher(body);
            return ParsedLog.event("mysql", "mysql_connection_refused", Severity.HIGH)
                    .field("target", target.find() ? target.group("target") : null)
                    .tags("mysql", "database", "availability");
        }
        if (lower.contains("too many connections")) {
            return ParsedLog.event("mysql", "mysql_too_many_connections", Severity.HIGH)
                    .fields(FieldValues.typed(Logfmt.parse(body)))
                    .tags("mysql", "database", "availability");
        }
        if (lower.contains("access denied")) {
            return ParsedLog.event("mysql", "mysql_access_denied", Severity.LOW).tags("mysql", "database", "auth");
        }
        if (lower.startsWith("backup ")) {
            boolean failed = lower.contains("fail");
            return ParsedLog.event("mysql", failed ? "mysql_backup_failed" : "mysql_backup_completed",
                            failed ? Severity.MEDIUM : Severity.INFO)
                    .fields(FieldValues.typed(Logfmt.parse(body)))
                    .tags("mysql", "database", "backup");
        }

        Matcher level = LEVEL.matcher(body);
        if (!level.matches()) {
            return null;
        }
        String message = level.group("message");
        String messageLower = message.toLowerCase(Locale.ROOT);
        ParsedLog.Builder event = ParsedLog.event("mysql", "mysql_log", severityOf(level.group("level")))
                .field("level", level.group("level"))
                .tags("mysql", "database");
        Matcher aborted = ABORTED.matcher(message);
        if (aborted.find()) {
            return event.type("mysql_aborted_connection", Severity.LOW)
                    .field("connection_id", Long.parseLong(aborted.group("id")))
                    .field("db", aborted.group("db"))
                    .field("user", aborted.group("user"))
                    .field("client_host", aborted.group("host"));
        }
        if (messageLower.startsWith("slow query") || messageLower.startsWith("query completed")) {
            Map<String, Object> pairs = FieldValues.typed(Logfmt.parse(message));
            double seconds = pairs.containsKey("query_time")
                    ? FieldValues.asDouble(pairs.get("query_time"), 0)
                    : FieldValues.asDouble(pairs.get("latency_ms"), 0) / 1000;
            event.fields(pairs).field("query_seconds", seconds);
            return seconds >= SLOW_SECONDS || messageLower.startsWith("slow query")
                    ? event.type("mysql_slow_query", Severity.MEDIUM)
                    : event.type("mysql_query", Severity.INFO);
        }
        if (messageLower.startsWith("connection accepted")) {
            return event.type("mysql_connection_accepted", Severity.INFO)
                    .fields(FieldValues.typed(Logfmt.parse(message)));
        }
        if (messageLower.contains("ready for connections")) {
            return event.type("mysql_ready", Severity.INFO);
        }
        if (messageLower.startsWith("innodb:")) {
            return event.type("mysql_innodb", Severity.INFO);
        }
        return event;
    }

    private static Severity severityOf(String level) {
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "error" -> Severity.MEDIUM;
            case "warning", "warn" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }
}
