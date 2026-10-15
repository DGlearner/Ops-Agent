package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Nginx access log in "combined" format, optionally followed by {@code key=value} extras such as upstream. */
public class NginxParser implements LogParser {

    private static final Pattern COMBINED = Pattern.compile(
            "^(?<ip>\\S+) \\S+ (?<user>\\S+) \\[(?<time>[^]]+)] \"(?<method>[A-Z]+) (?<path>\\S+) (?<protocol>[^\"]+)\" "
                    + "(?<status>\\d{3}) (?<bytes>\\d+|-) \"(?<referer>[^\"]*)\" \"(?<agent>[^\"]*)\"(?<extra>.*)$");

    /** Requests at or above this duration are tagged as slow. */
    private static final double SLOW_SECONDS = 1.0;

    @Override
    public SourceType sourceType() {
        return SourceType.NGINX;
    }

    @Override
    public Optional<ParsedLog> parse(RawLogRecord raw) {
        Matcher m = COMBINED.matcher(raw.message());
        if (!m.matches()) {
            return Optional.empty();
        }
        int status = Integer.parseInt(m.group("status"));
        var extra = FieldValues.typed(Logfmt.parse(m.group("extra")));
        double requestTime = FieldValues.asDouble(extra.get("request_time"), 0);
        boolean slow = requestTime >= SLOW_SECONDS;

        ParsedLog.Builder event = ParsedLog.event("nginx", "http_request", Severity.INFO);
        if (status >= 500) {
            event.type("http_server_error", Severity.MEDIUM);
        } else if (status >= 400) {
            event.type("http_client_error", Severity.INFO);
        }
        return Optional.of(event
                .timestamp(Timestamps.nginx(m.group("time")))
                .field("client_ip", m.group("ip"))
                .field("method", m.group("method"))
                .field("path", m.group("path"))
                .field("protocol", m.group("protocol"))
                .field("status", (long) status)
                .field("bytes", FieldValues.typed(m.group("bytes")))
                .field("user_agent", m.group("agent"))
                .fields(extra)
                .field("slow", slow ? Boolean.TRUE : null)
                .tags(slow ? new String[] {"http", "slow"} : new String[] {"http"})
                .build());
    }
}
