package io.github.dglearner.opsagent.core.remediation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Whether the case recovered after a remediation, judged once its window is over. token: only for the claimer. */
public record VerificationView(UUID verificationId, UUID caseId, UUID remediationId, String runId,
        Map<String, Object> criteria,
        Instant windowFrom, Instant notBefore, String status, String leaseOwner, Instant leaseUntil, Integer token,
        int attempts, String outcome, Map<String, Object> result, Instant createdAt, Instant completedAt) {
}
