package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A rule crossed its threshold. Topic {@link Topics#ALERTS}, key = {@code group_key}.
 *
 * @param alertId     deterministic: derived from the fingerprint and the window start, so replays produce the same id
 * @param fingerprint rule id plus group values, e.g. {@code ssh-brute-force|host=app-01|fields.src_ip=1.2.3.4}
 * @param groupKey    correlation group; the service (or host) until topology information is applied
 * @param groupValues values of the rule's {@code group_by} attributes
 * @param samples     up to five of the matching events, as evidence: what they said, when, and the trace to follow
 */
public record Alert(
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
        List<UUID> sampleEventIds,
        String summary,
        Instant createdAt,
        List<Sample> samples) {

    /** One matching event: its id, its time, its (redacted, shortened) message and its trace id if it had one. */
    public record Sample(UUID eventId, Instant timestamp, String message, String traceId) {
    }

    public Alert {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(groupKey, "groupKey");
        groupValues = groupValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(groupValues));
        sampleEventIds = sampleEventIds == null ? List.of() : List.copyOf(sampleEventIds);
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public Alert withGroupKey(String newGroupKey) {
        return new Alert(alertId, ruleId, ruleVersion, fingerprint, severity, newGroupKey, service, host, groupValues,
                windowStart, windowEnd, count, threshold, firstEventAt, lastEventAt, sampleEventIds, summary, createdAt,
                samples);
    }
}
