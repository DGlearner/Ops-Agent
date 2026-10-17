package io.github.dglearner.opsagent.core.incident;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An incident as the API returns it. {@code sharedDependencies} is a hint computed from the catalog: what every
 * subject depends on. It is not a root cause.
 */
public record IncidentView(
        UUID incidentId,
        String title,
        String status,
        Severity severity,
        List<String> services,
        List<String> subjects,
        List<String> sharedDependencies,
        int alertCount,
        Instant openedAt,
        Instant lastAlertAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        Instant closedAt,
        int version) {
}
