package io.github.dglearner.opsagent.core.casework;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A case with everything recorded on it. version moves on every write; state_version only when what an action is
 * decided on changes (status, facts, constraints, decisions, incidents), so a hypothesis noted after a card was
 * proposed does not make the card stale.
 */
public record CaseView(UUID caseId, String title, String status, String goal, String runbook, int version,
        int stateVersion, List<UUID> incidentIds, List<Entry> entries, List<Event> events, Instant createdAt,
        Instant updatedAt, Instant resolvedAt) {

    public record Entry(UUID entryId, String kind, String status, String text, String sourceType, String sourceRef,
            List<Map<String, Object>> evidence, Map<String, Object> data, UUID supersedes, UUID supersededBy,
            String runId, Instant createdAt, Instant updatedAt) {
    }

    public record Event(UUID eventId, String type, String actor, Map<String, Object> payload, Instant createdAt) {
    }
}
