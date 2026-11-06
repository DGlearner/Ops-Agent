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
 * Docker events, as {@code docker events --format '{{json .}}'} writes them, and daemon and containerd lines:
 * {@code <ISO time> dockerd[pid]: event=die container=... exitCode=137}.
 */
public class DockerParser implements LogParser {

    private static final Pattern LINE = Pattern.compile(
            "^(?<time>\\d{4}-\\d{2}-\\d{2}T\\S+)\\s+(?<program>[\\w.-]+)(?:\\[(?<pid>\\d+)])?:\\s+(?<rest>.*)$");

    @Override
    public SourceType sourceType() {
        return SourceType.DOCKER;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        if (raw.message().startsWith("{")) {
            return EcsJson.parse(raw.message()).filter(json -> json.containsKey("Action")).map(DockerParser::event);
        }
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

    /**
     * One event of {@code docker events}. An out-of-memory kill is an {@code oom} event before the {@code die}; health
     * checks run as {@code exec_create}, {@code exec_start} and {@code exec_die}, with the command after the colon.
     * A container's name is the service, unless the shipper declares one; networks, volumes and images have none.
     */
    private static ParsedLog event(Map<String, Object> json) {
        String action = json.get("Action").toString();
        int colon = action.indexOf(':');
        String verb = (colon < 0 ? action : action.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String detail = colon < 0 ? null : action.substring(colon + 1).trim();
        Object name = json.get("Actor.Attributes.name");
        Long exitCode = json.containsKey("Actor.Attributes.exitCode")
                ? FieldValues.asLong(json.get("Actor.Attributes.exitCode"), -1) : null;
        boolean container = "container".equals(lower(json.get("Type")));
        ParsedLog.Builder parsed = !container
                ? ParsedLog.event("docker", "docker_event", Severity.INFO)
                : switch (verb) {
                    case "oom" -> ParsedLog.event("container", "container_oom_killed", Severity.HIGH);
                    case "die" -> exitCode != null && exitCode != 0
                            ? ParsedLog.event("container", "container_died", Severity.MEDIUM)
                            : ParsedLog.event("container", "container_stopped", Severity.INFO);
                    case "restart" -> ParsedLog.event("container", "container_restarted", Severity.MEDIUM);
                    case "health_status" -> "unhealthy".equalsIgnoreCase(detail)
                            ? ParsedLog.event("container", "container_unhealthy", Severity.MEDIUM)
                            : ParsedLog.event("container", "container_healthy", Severity.INFO);
                    case "start" -> ParsedLog.event("container", "container_started", Severity.INFO);
                    case "exec_create", "exec_start", "exec_die" ->
                            ParsedLog.event("container", "container_exec", Severity.INFO);
                    default -> ParsedLog.event("container", "container_event", Severity.INFO);
                };
        long nanos = FieldValues.asLong(json.get("timeNano"), -1);
        return parsed
                .timestamp(nanos < 0 ? null : Instant.ofEpochSecond(0, nanos))
                .service(container && name != null ? name.toString() : null)
                .field("event", verb)
                .field("docker_type", json.get("Type"))
                .field(container ? "container" : "name", name)
                .field("container_id", json.get("Actor.ID"))
                .field("image", json.get("Actor.Attributes.image"))
                .field("exitCode", exitCode)
                .field("detail", detail)
                .tags("container", "docker")
                .build();
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
