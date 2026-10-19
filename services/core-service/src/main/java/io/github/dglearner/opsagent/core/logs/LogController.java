package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/logs} (docs/project-design.md §8.7). The range defaults to the last hour and is at most
 * {@code ops.logs.max-range}; a text to look for needs three characters at least, what the trigram index can serve.
 */
@RestController
@RequestMapping("/api/v1/logs")
class LogController {

    private static final int MAX_LIMIT = 200;
    private static final Duration DEFAULT_RANGE = Duration.ofHours(1);

    private final Logs logs;
    private final LogProperties properties;
    private final Clock clock;

    LogController(Logs logs, LogProperties properties, Clock clock) {
        this.logs = logs;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    LogPage search(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String service, @RequestParam(name = "min_severity", required = false)
            String minSeverity, @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        return logs.search(filter(from, to, service, minSeverity, eventType, traceId, q), limit, cursor);
    }

    @GetMapping("/counts")
    List<LogCount> counts(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String service, @RequestParam(name = "min_severity", required = false)
            String minSeverity, @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "event_type") String by,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        Logs.CountBy countBy;
        try {
            countBy = Logs.CountBy.valueOf(by.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("by must be one of event_type, severity, service, host");
        }
        return logs.count(filter(from, to, service, minSeverity, eventType, traceId, q), countBy, limit);
    }

    private LogFilter filter(Instant from, Instant to, String service, String minSeverity, String eventType,
            String traceId, String q) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_RANGE);
        if (!start.isBefore(end)) {
            throw invalid("from must be before to");
        }
        if (Duration.between(start, end).compareTo(properties.maxRange()) > 0) {
            throw invalid("the range is at most " + properties.maxRange().toHours() + " h");
        }
        String text = q == null || q.isBlank() ? null : q.strip();
        if (text != null && (text.length() < 3 || text.length() > 200)) {
            throw invalid("q must be 3 to 200 characters");
        }
        Severity severity = null;
        if (minSeverity != null) {
            try {
                severity = Severity.valueOf(minSeverity.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid("min_severity must be one of info, low, medium, high, critical");
            }
        }
        return new LogFilter(start, end, service, severity, eventType, traceId, text);
    }

    private static ApiProblem invalid(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }
}
