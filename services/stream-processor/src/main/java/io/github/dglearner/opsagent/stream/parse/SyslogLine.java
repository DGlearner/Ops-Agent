package io.github.dglearner.opsagent.stream.parse;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The syslog envelope {@code <time> host program[pid]: body}, shared by Linux and MySQL logs. The time is either BSD
 * ({@code Mon DD HH:MM:SS}) or RFC 3339 ({@code 2026-10-29T13:03:24.510115+08:00}), which rsyslog writes by default
 * since Debian 12.
 */
record SyslogLine(Instant timestamp, String host, String program, Long pid, String body) {

    private static final String REST = "\\s(?<host>\\S+)\\s(?<program>[^\\s\\[:]+)(?:\\[(?<pid>\\d+)])?:\\s?(?<body>.*)$";
    private static final Pattern BSD =
            Pattern.compile("^(?<month>[A-Z][a-z]{2})\\s+(?<day>\\d{1,2})\\s(?<time>\\d{2}:\\d{2}:\\d{2})" + REST);
    private static final Pattern RFC3339 = Pattern.compile(
            "^(?<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))" + REST);

    static Optional<SyslogLine> parse(String line, Instant receivedAt) {
        Matcher m = RFC3339.matcher(line);
        Instant timestamp;
        if (m.matches()) {
            timestamp = Timestamps.iso(m.group("time"));
        } else if ((m = BSD.matcher(line)).matches()) {
            timestamp = Timestamps.syslog(m.group("month"), m.group("day"), m.group("time"), receivedAt);
        } else {
            return Optional.empty();
        }
        Long pid = m.group("pid") == null ? null : Long.parseLong(m.group("pid"));
        return Optional.of(new SyslogLine(timestamp, m.group("host"), m.group("program"), pid, m.group("body")));
    }
}
