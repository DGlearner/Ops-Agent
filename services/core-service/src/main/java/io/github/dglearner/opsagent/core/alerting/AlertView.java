package io.github.dglearner.opsagent.core.alerting;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An alert as the API returns it: the Kafka message's fields, the incident it belongs to and when it was stored. */
public record AlertView(
        UUID alertId,
        String ruleId,
        int ruleVersion,
        String fingerprint,
        Severity severity,
        String groupKey,
        String service,
        String host,
        Map<String, String> groupValues,
        Instant windowStart,
        Instant windowEnd,
        long count,
        int threshold,
        Instant firstEventAt,
        Instant lastEventAt,
        String summary,
        List<UUID> sampleEventIds,
        List<Alert.Sample> samples,
        UUID incidentId,
        Instant createdAt,
        Instant storedAt) {
}
