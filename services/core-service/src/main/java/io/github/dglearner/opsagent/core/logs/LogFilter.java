package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;

/**
 * What a search or a count looks at: lines whose own time is in [from, to), optionally of one service, at least this
 * severe, of one event type, of one trace, or containing a text (case-insensitive).
 */
public record LogFilter(Instant from, Instant to, String service, Severity minSeverity, String eventType,
        String traceId, String text) {
}
