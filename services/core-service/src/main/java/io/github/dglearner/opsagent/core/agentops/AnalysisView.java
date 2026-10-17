package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.contracts.AgentResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An analysis of an incident by the agent. {@code hypotheses} only cite evidence the incident really has; anything
 * else the agent cited is in {@code rejectedEvidence}.
 *
 * @param status {@code pending}, {@code completed}, {@code failed} or {@code timed_out}
 */
public record AnalysisView(UUID analysisId, UUID incidentId, String status, String requestedBy, Instant requestedAt,
        Instant completedAt, String summary, Boolean uncertain, List<AgentResult.Hypothesis> hypotheses,
        List<String> nextSteps, List<AgentResult.Evidence> rejectedEvidence, String model, Integer toolCalls,
        Long durationMs, String error) {
}
