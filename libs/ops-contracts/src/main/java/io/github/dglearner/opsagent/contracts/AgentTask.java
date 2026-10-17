package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Work for the agent runtime (docs/project-design.md §7.6). Topic {@link Topics#AGENT_TASKS_HIGH}, key =
 * {@code task_id}. The agent reads what it needs through core-service's read-only API; the task only says what to
 * look at.
 *
 * @param kind        {@code incident_analysis}: explain an incident and attribute it to a likely cause
 * @param requestedBy {@code system} when dispatched automatically, else {@code user}
 * @param deadline    when core-service stops waiting for a result
 * @param language    language of the human-readable parts of the result, e.g. {@code zh}
 */
public record AgentTask(UUID taskId, String kind, UUID incidentId, String requestedBy, Instant requestedAt,
        Instant deadline, String language) {

    public static final String INCIDENT_ANALYSIS = "incident_analysis";

    public AgentTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(incidentId, "incidentId");
    }
}
