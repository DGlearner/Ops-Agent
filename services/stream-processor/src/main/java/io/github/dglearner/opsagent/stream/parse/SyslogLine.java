package io.github.dglearner.opsagent.stream.parse;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The BSD syslog envelope {@code Mon DD HH:MM:SS host program[pid]: body}, shared by Linux and MySQL logs. */
record SyslogLine(Instant timestamp, String host, String program, Long pid, String body) {

    private static final Pattern LINE = Pattern.compile(
            "^(?<month>[A-Z][a-z]{2})\\s+(?<day>\\d{1,2})\\s(?<time>\\d{2}:\\d{2}:\\d{2})\\s(?<host>\\S+)\\s"
                    + "(?<program>[^\\s\\[:]+)(?:\\[(?<pid>\\d+)])?:\\s?(?<body>.*)$");

    static Optional<SyslogLine> parse(String line, Instant receivedAt) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        Long pid = m.group("pid") == null ? null : Long.parseLong(m.group("pid"));
        return Optional.of(new SyslogLine(Timestamps.syslog(m.group("month"), m.group("day"), m.group("time"),
                receivedAt), m.group("host"), m.group("program"), pid, m.group("body")));
    }
}
