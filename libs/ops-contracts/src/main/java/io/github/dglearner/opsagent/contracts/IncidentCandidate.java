package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Alerts of one dependency group merged by a session window. Topic {@link Topics#INCIDENT_CANDIDATES},
 * key = {@code group_key}. A new version is emitted every time the session changes.
 *
 * @param mergedFrom start times of earlier sessions that this session absorbed; core-service merges their
 *                   incidents into this one
 */
public record IncidentCandidate(
        String groupKey,
        Instant sessionStart,
        Instant sessionEnd,
        List<UUID> alertIds,
        List<String> services,
        List<String> hosts,
        Severity maxSeverity,
        int alertCount,
        List<Instant> mergedFrom) {

    public IncidentCandidate {
        Objects.requireNonNull(groupKey, "groupKey");
        Objects.requireNonNull(sessionStart, "sessionStart");
        Objects.requireNonNull(sessionEnd, "sessionEnd");
        Objects.requireNonNull(maxSeverity, "maxSeverity");
        alertIds = alertIds == null ? List.of() : List.copyOf(alertIds);
        services = services == null ? List.of() : List.copyOf(services);
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        mergedFrom = mergedFrom == null ? List.of() : List.copyOf(mergedFrom);
    }
}
