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
 * Linux syslog: sshd, sudo, cron, systemd, kernel and node-exporter disk checks. Lines from other programs become
 * generic {@code syslog} events, so only lines without a syslog envelope are unparsed.
 */
public class LinuxParser implements LogParser {

    private static final Pattern SSH_FAILED = Pattern.compile(
            "^Failed (?<method>\\S+) for (?<invalid>invalid user )?(?<user>\\S+) from (?<ip>\\S+) port (?<port>\\d+)");
    private static final Pattern SSH_INVALID_USER =
            Pattern.compile("^Invalid user (?<user>\\S*) from (?<ip>\\S+)(?: port (?<port>\\d+))?");
    private static final Pattern SSH_ACCEPTED = Pattern.compile(
            "^Accepted (?<method>\\S+) for (?<user>\\S+) from (?<ip>\\S+) port (?<port>\\d+)");
    private static final Pattern SUDO = Pattern.compile("^\\s*(?<user>\\S+) : (?<details>.*)$");
    private static final Pattern CRON = Pattern.compile("^\\((?<user>[^)]+)\\) CMD \\((?<command>.*)\\)$");
    private static final Pattern SYSTEMD_STARTED = Pattern.compile("^Started (?<unit>.+?)\\.?$");
    private static final Pattern SYSTEMD_FAILED =
            Pattern.compile("^(?:Failed to start (?<unit>.+?)\\.?|(?<unit2>\\S+): Failed with result .*)$");
    private static final Pattern KERNEL_OOM =
            Pattern.compile("Out of memory: Killed process (?<pid>\\d+) \\((?<process>[^)]+)\\)");
    private static final Pattern DISK_CHECK = Pattern.compile("^disk_check (?<pairs>.*)$");

    @Override
    public SourceType sourceType() {
        return SourceType.LINUX;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        return SyslogLine.parse(raw.message(), raw.receivedAt()).map(line -> {
            ParsedLog.Builder event = switch (line.program().toLowerCase(Locale.ROOT)) {
                case "sshd" -> ssh(line.body());
                case "sudo" -> sudo(line.body());
                case "cron", "crond" -> cron(line.body());
                case "systemd" -> systemd(line.body());
                case "kernel" -> kernel(line.body());
                case "node-exporter" -> diskCheck(line.body());
                default -> ParsedLog.event(line.program(), "syslog", Severity.INFO).tags("linux");
            };
            return event.timestamp(line.timestamp())
                    .field("program", line.program())
                    .field("pid", line.pid())
                    .build();
        });
    }

    private static ParsedLog.Builder ssh(String body) {
        Matcher m;
        if ((m = SSH_FAILED.matcher(body)).find()) {
            return ParsedLog.event("ssh", "ssh_login_failed", Severity.LOW)
                    .field("user", m.group("user"))
                    .field("src_ip", m.group("ip"))
                    .field("port", Long.parseLong(m.group("port")))
                    .field("method", m.group("method"))
                    .field("invalid_user", m.group("invalid") != null)
                    .tags("linux", "auth", "ssh", "security");
        }
        if ((m = SSH_INVALID_USER.matcher(body)).find()) {
            return ParsedLog.event("ssh", "ssh_invalid_user", Severity.LOW)
                    .field("user", m.group("user"))
                    .field("src_ip", m.group("ip"))
                    .field("port", m.group("port") == null ? null : Long.parseLong(m.group("port")))
                    .tags("linux", "auth", "ssh", "security");
        }
        if ((m = SSH_ACCEPTED.matcher(body)).find()) {
            return ParsedLog.event("ssh", "ssh_login_success", Severity.INFO)
                    .field("user", m.group("user"))
                    .field("src_ip", m.group("ip"))
                    .field("port", Long.parseLong(m.group("port")))
                    .field("method", m.group("method"))
                    .tags("linux", "auth", "ssh");
        }
        return ParsedLog.event("ssh", "ssh_event", Severity.INFO).tags("linux", "ssh");
    }

    /** {@code alice : TTY=pts/1 ; PWD=/home/alice ; USER=root ; COMMAND=/usr/bin/systemctl status nginx} */
    private static ParsedLog.Builder sudo(String body) {
        ParsedLog.Builder event = ParsedLog.event("sudo", "sudo_command", Severity.INFO).tags("linux", "auth", "sudo");
        Matcher m = SUDO.matcher(body);
        if (m.matches()) {
            event.field("user", m.group("user"));
            for (String part : m.group("details").split(" ; ")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    event.field(key.equals("user") ? "run_as" : key, part.substring(eq + 1).trim());
                }
            }
        }
        return event;
    }

    private static ParsedLog.Builder cron(String body) {
        ParsedLog.Builder event = ParsedLog.event("cron", "cron_job", Severity.INFO).tags("linux", "cron");
        Matcher m = CRON.matcher(body);
        if (m.matches()) {
            event.field("user", m.group("user")).field("command", m.group("command"));
        }
        return event;
    }

    private static ParsedLog.Builder systemd(String body) {
        Matcher m;
        if ((m = SYSTEMD_FAILED.matcher(body)).matches()) {
            String unit = m.group("unit") != null ? m.group("unit") : m.group("unit2");
            return ParsedLog.event("systemd", "systemd_unit_failed", Severity.MEDIUM)
                    .field("unit", unit).tags("linux", "systemd");
        }
        if ((m = SYSTEMD_STARTED.matcher(body)).matches()) {
            return ParsedLog.event("systemd", "systemd_unit_started", Severity.INFO)
                    .field("unit", m.group("unit")).tags("linux", "systemd");
        }
        return ParsedLog.event("systemd", "systemd_event", Severity.INFO).tags("linux", "systemd");
    }

    private static ParsedLog.Builder kernel(String body) {
        Matcher m = KERNEL_OOM.matcher(body);
        if (m.find()) {
            return ParsedLog.event("kernel", "kernel_oom", Severity.HIGH)
                    .field("killed_pid", Long.parseLong(m.group("pid")))
                    .field("killed_process", m.group("process"))
                    .tags("linux", "kernel", "memory");
        }
        return ParsedLog.event("kernel", "kernel_event", Severity.INFO).tags("linux", "kernel");
    }

    /** {@code disk_check mount=/ usage_percent=75 status=ok}; severity follows the reported status. */
    private static ParsedLog.Builder diskCheck(String body) {
        Matcher m = DISK_CHECK.matcher(body);
        if (!m.matches()) {
            return ParsedLog.event("node-exporter", "syslog", Severity.INFO).tags("linux");
        }
        Map<String, Object> pairs = FieldValues.typed(Logfmt.parse(m.group("pairs")));
        Severity severity = switch (String.valueOf(pairs.get("status")).toLowerCase(Locale.ROOT)) {
            case "critical" -> Severity.HIGH;
            case "warning", "warn" -> Severity.MEDIUM;
            default -> Severity.INFO;
        };
        return ParsedLog.event("disk", "disk_usage", severity).fields(pairs).tags("linux", "disk", "capacity");
    }
}
