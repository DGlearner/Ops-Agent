package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nginx access log in "combined" format, optionally followed by {@code key=value} extras such as upstream; and the
 * error log, {@code 2026/10/02 03:04:05 [error] 811#811: *42 <message>, client: ..., upstream: "...", ...}.
 */
public class NginxParser implements LogParser {

    private static final Pattern COMBINED = Pattern.compile(
            "^(?<ip>\\S+) \\S+ (?<user>\\S+) \\[(?<time>[^]]+)] \"(?<method>[A-Z]+) (?<path>\\S+) (?<protocol>[^\"]+)\" "
                    + "(?<status>\\d{3}) (?<bytes>\\d+|-) \"(?<referer>[^\"]*)\" \"(?<agent>[^\"]*)\"(?<extra>.*)$");
    private static final Pattern ERROR = Pattern.compile(
            "^(?<time>\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(?<level>[a-z]+)] \\d+#\\d+: (?:\\*\\d+ )?"
                    + "(?<text>.*)$");
    /** The request context nginx appends to an error: {@code , client: 203.0.113.9, request: "GET / HTTP/1.1"}. */
    private static final Pattern CONTEXT = Pattern.compile(", (?<key>client|server|request|upstream|host|referrer"
            + "|subrequest): (?:\"(?<quoted>[^\"]*)\"|(?<plain>[^,]*))");

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
            m = ERROR.matcher(raw.message());
            return m.matches() ? Optional.of(error(m)) : Optional.empty();
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

    /**
     * Errors talking to an upstream (refused, timed out, closed early, none left) are server errors; the others at
     * {@code error} or {@code warn} are mostly caused by clients, such as a scanner asking for files that do not exist.
     */
    private static ParsedLog error(Matcher m) {
        String level = m.group("level");
        String text = m.group("text");
        int contextStart = text.indexOf(", client: ");
        String error = contextStart < 0 ? text : text.substring(0, contextStart);
        ParsedLog.Builder event = error.contains("upstream")
                ? ParsedLog.event("nginx", "http_upstream_error", Severity.MEDIUM)
                        .field("upstream_error", upstreamError(error))
                : ParsedLog.event("nginx", "nginx_error_log", switch (level) {
                    case "emerg", "alert", "crit" -> Severity.HIGH;
                    case "error", "warn" -> Severity.LOW;
                    default -> Severity.INFO;
                });
        if (contextStart >= 0) {
            Matcher c = CONTEXT.matcher(text.substring(contextStart));
            while (c.find()) {
                String key = c.group("key").equals("client") ? "client_ip" : c.group("key");
                event.field(key, c.group("quoted") != null ? c.group("quoted") : c.group("plain").trim());
            }
        }
        return event
                .timestamp(Timestamps.nginxError(m.group("time")))
                .field("level", level)
                .field("error", error)
                .tags("http", "nginx-error")
                .build();
    }

    private static String upstreamError(String error) {
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("connection refused")) {
            return "refused";
        }
        if (lower.contains("timed out")) {
            return "timed_out";
        }
        if (lower.contains("no live upstreams")) {
            return "no_live_upstreams";
        }
        if (lower.contains("prematurely closed") || lower.contains("reset by peer")) {
            return "closed";
        }
        return "other";
    }
}
