package io.github.dglearner.opsagent.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The agent's answer to an {@link AgentTask}. Topic {@link Topics#AGENT_RESULTS}, key = {@code task_id}.
 * core-service checks it before storing it: every piece of evidence must be something the incident really has.
 *
 * @param status     {@code completed} or {@code failed} (then {@code error} says why)
 * @param summary    what happened, in a few sentences
 * @param uncertain  the evidence does not support a conclusion; the hypotheses are guesses
 * @param hypotheses likely causes, most likely first
 * @param nextSteps  what an operator could check or do next; the agent itself does nothing
 * @param toolCalls  how many read-only tools the agent called
 */
public record AgentResult(UUID taskId, UUID incidentId, String status, String summary, boolean uncertain,
        List<Hypothesis> hypotheses, List<String> nextSteps, String model, int toolCalls, long durationMs,
        String error, Instant completedAt) {

    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    /**
     * A likely cause.
     *
     * @param component  the catalog entry it is attributed to, e.g. {@code shop-mysql}
     * @param confidence {@code high}, {@code medium} or {@code low}
     */
    public record Hypothesis(String component, String cause, String confidence, List<Evidence> evidence) {

        public Hypothesis {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * What a hypothesis rests on.
     *
     * @param kind {@code alert} (an alert id), {@code event} (a sample event id), {@code topology} (a catalog entry)
     *             or {@code incident} (the incident id)
     */
    public record Evidence(String kind, String id, String note) {
    }

    public AgentResult {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(status, "status");
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        nextSteps = nextSteps == null ? List.of() : List.copyOf(nextSteps);
    }
}
