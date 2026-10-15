package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Docker daemon and containerd events: {@code <ISO time> dockerd[pid]: event=die container=... exitCode=137}. */
public class DockerParser implements LogParser {

    private static final Pattern LINE = Pattern.compile(
            "^(?<time>\\d{4}-\\d{2}-\\d{2}T\\S+)\\s+(?<program>[\\w.-]+)(?:\\[(?<pid>\\d+)])?:\\s+(?<rest>.*)$");

    @Override
    public SourceType sourceType() {
        return SourceType.DOCKER;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        Matcher m = LINE.matcher(raw.message());
        if (!m.matches()) {
            return Optional.empty();
        }
        Map<String, Object> fields = FieldValues.typed(Logfmt.parse(m.group("rest")));
        String event = lower(fields.get("event"));
        ParsedLog.Builder parsed = switch (event) {
            case "die" -> die(fields);
            case "restart" -> ParsedLog.event("container", "container_restarted", Severity.MEDIUM);
            case "health_status" -> "unhealthy".equals(lower(fields.get("status")))
                    ? ParsedLog.event("container", "container_unhealthy", Severity.MEDIUM)
                    : ParsedLog.event("container", "container_healthy", Severity.INFO);
            case "start" -> ParsedLog.event("container", "container_started", Severity.INFO);
            case "" -> containerLog(fields);
            default -> ParsedLog.event("container", "container_event", Severity.INFO);
        };
        return Optional.of(parsed
                .timestamp(Timestamps.iso(m.group("time")))
                .field("program", m.group("program"))
                .field("pid", m.group("pid") == null ? null : Long.parseLong(m.group("pid")))
                .fields(fields)
                .tags("container", "docker")
                .build());
    }

    private static ParsedLog.Builder die(Map<String, Object> fields) {
        if (Boolean.TRUE.equals(fields.get("OOMKilled"))) {
            return ParsedLog.event("container", "container_oom_killed", Severity.HIGH);
        }
        if (FieldValues.asLong(fields.get("exitCode"), 0) != 0) {
            return ParsedLog.event("container", "container_died", Severity.MEDIUM);
        }
        return ParsedLog.event("container", "container_stopped", Severity.INFO);
    }

    /** containerd forwards container output as {@code log_level=... message="..."}. */
    private static ParsedLog.Builder containerLog(Map<String, Object> fields) {
        String level = lower(fields.get("log_level"));
        return level.equals("error") || level.equals("fatal")
                ? ParsedLog.event("container", "container_log_error", Severity.MEDIUM)
                : ParsedLog.event("container", "container_log", Severity.INFO);
    }

    private static String lower(Object value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
    }
}
