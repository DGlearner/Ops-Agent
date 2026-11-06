package io.github.dglearner.opsagent.core.remediation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One run of a confirmed card: what was asked, checked, done and read back; run_id: the chat run that proposed it. */
public record RemediationView(UUID remediationId, String actionId, UUID caseId, UUID incidentId, String op,
        Map<String, Object> params, String payloadDigest, String status, Map<String, Object> precheck,
        Map<String, Object> receipt, String runId, String sessionId, String snapshotDigest, Instant createdAt,
        Instant finishedAt) {
}
