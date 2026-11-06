package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.core.casework.CaseStatus;
import io.github.dglearner.opsagent.core.casework.Cases;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a case recovered after a remediation (docs/agent-design.md §6, §13.2). A worker claims a due verification
 * and gets a lease and a token; a result counts only with the current token, so a worker whose lease ran out and
 * answers late cannot overwrite the one that took over. A verification made stale by a newer remediation keeps its
 * late result for the record but moves nothing. A verification never resolves a case: a person signs it off.
 */
@Service
public class Verifications {

    static final Set<String> OUTCOMES = Set.of("recovered", "not_recovered", "inconclusive");

    private final RemediationStore store;
    private final Cases cases;
    private final LabProperties properties;

    Verifications(RemediationStore store, Cases cases, LabProperties properties) {
        this.store = store;
        this.cases = cases;
        this.properties = properties;
    }

    public Optional<VerificationView> claim(String worker) {
        if (worker == null || worker.isBlank() || worker.length() > 100) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "worker is required");
        }
        return store.claim(worker, properties.leaseDuration());
    }

    @Transactional
    public VerificationView complete(UUID id, Integer token, String outcome, Map<String, Object> result) {
        if (token == null || outcome == null || !OUTCOMES.contains(outcome)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "token and an outcome (" + OUTCOMES + ") are required");
        }
        VerificationView current = store.verification(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "VERIFICATION_NOT_FOUND", "No verification " + id));
        Map<String, Object> kept = result == null ? Map.of() : result;
        if (current.status().equals("stale")) {
            store.keepStaleResult(id, token, outcome, kept);
            return store.verification(id).orElseThrow();
        }
        if (store.complete(id, token, outcome, kept) != 1) {
            throw new ApiProblem(HttpStatus.CONFLICT, "STALE_TOKEN",
                    "This verification is no longer yours (another worker took it over, or it is done)");
        }
        cases.record(current.caseId(), "VERIFICATION_DONE", Map.of("verification_id", id, "outcome", outcome));
        switch (outcome) {
            case "recovered" -> cases.advance(current.caseId(), CaseStatus.MONITORING, null, null);
            case "not_recovered" -> cases.advance(current.caseId(), CaseStatus.DIAGNOSING, null, null);
            default -> {
                // inconclusive: the case waits in verifying for a person.
            }
        }
        return store.verification(id).orElseThrow();
    }

    public List<VerificationView> forCase(UUID caseId) {
        return store.verifications(caseId);
    }
}
