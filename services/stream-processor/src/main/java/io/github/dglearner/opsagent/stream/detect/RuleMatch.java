package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One event matched one rule. Carries a snapshot of the rule, so window aggregation and alerting do not need to look
 * the rule up again (and an alert reflects the rule version that matched). Keyed by {@code fingerprint}.
 */
public record RuleMatch(
        String ruleId,
        int ruleVersion,
        Duration window,
        int threshold,
        Severity severity,
        Duration suppressFor,
        String summaryTemplate,
        String fingerprint,
        Map<String, String> groupValues,
        UUID eventId,
        Instant eventTime,
        String service,
        String host,
        String message,
        String traceId) {

    /** Correlation group until topology information is applied: the service, else the host. */
    public String groupKey() {
        return service != null ? service : host;
    }
}
