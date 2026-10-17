package io.github.dglearner.opsagent.core.incident;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One event of an incident's history: OPENED, ALERT_ATTACHED or STATUS_CHANGED, by the system or a user. */
public record TimelineEntry(UUID eventId, String type, String actorType, String fromStatus, String toStatus,
        Map<String, Object> payload, Instant at) {
}
