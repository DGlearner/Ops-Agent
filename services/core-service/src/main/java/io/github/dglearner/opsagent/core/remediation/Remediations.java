package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.core.casework.CaseStatus;
import io.github.dglearner.opsagent.core.casework.CaseView;
import io.github.dglearner.opsagent.core.casework.Cases;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a card the user confirmed, once (docs/agent-design.md §5.3, §13.2). The digest is checked against what would
 * run, the case against the state the card was decided on; the run is registered before anything is done (the card's
 * action_id is unique), the operation's preconditions are checked again against the lab, and the receipt is what the
 * lab looks like afterwards. Then the case waits for its verification. The lab is called outside any transaction.
 */
@Service
public class Remediations {

    public record Request(String actionId, UUID caseId, UUID incidentId, String op, Map<String, Object> params,
            String payloadDigest, Integer expectedCaseVersion, String runId, String sessionId, String snapshotDigest) {
    }

    private static final int MAX_ORIGIN = 100;

    private static final Logger log = LoggerFactory.getLogger(Remediations.class);

    private final RemediationStore store;
    private final Cases cases;
    private final LabOperator lab;
    private final LabProperties properties;
    private final TransactionTemplate tx;

    Remediations(RemediationStore store, Cases cases, LabOperator lab, LabProperties properties,
            TransactionTemplate tx) {
        this.store = store;
        this.cases = cases;
        this.lab = lab;
        this.properties = properties;
        this.tx = tx;
    }

    /** The remediation, run now or already run for this action_id (the same receipt again). */
    public RemediationView execute(Request request) {
        if (request.actionId() == null || request.actionId().isBlank() || request.actionId().length() > 64) {
            throw invalid("action_id is required, at most 64 characters");
        }
        if (request.caseId() == null || request.payloadDigest() == null || request.expectedCaseVersion() == null) {
            throw invalid("case_id, payload_digest and expected_case_version are required");
        }
        if (tooLong(request.runId()) || tooLong(request.sessionId()) || tooLong(request.snapshotDigest())) {
            throw invalid("run_id, session_id and snapshot_digest are at most " + MAX_ORIGIN + " characters");
        }
        Map<String, Object> params;
        try {
            params = Operations.params(request.op(), request.params(), properties.containers().keySet());
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
        String digest = Digest.of(payload(request, params));
        var existing = store.byAction(request.actionId());
        if (existing.isPresent()) {
            if (!existing.get().payloadDigest().equals(digest)) {
                throw new ApiProblem(HttpStatus.CONFLICT, "ACTION_REUSED",
                        "This action_id already ran something else");
            }
            return existing.get();
        }
        if (!digest.equals(request.payloadDigest())) {
            throw new ApiProblem(HttpStatus.CONFLICT, "PAYLOAD_MISMATCH",
                    "The digest is not that of this operation and parameters; what was confirmed is not what would run");
        }
        UUID id = Ids.newId();
        try {
            tx.executeWithoutResult(status -> accept(id, request, params, digest));
        } catch (DuplicateKeyException e) {
            return store.byAction(request.actionId()).orElseThrow();
        }

        Map<String, Object> precheck;
        Operations.Check check;
        try {
            check = Operations.precheck(request.op(), params, lab);
        } catch (LabOperator.LabException e) {
            return finish(id, request, "blocked", Map.of("error", e.getMessage()),
                    receipt("blocked", "没有执行：读不到靶场的当前状态（" + e.getMessage() + "）", Map.of(), null));
        }
        precheck = new LinkedHashMap<>(check.observed());
        precheck.put("holds", check.holds());
        precheck.put("detail", check.detail());
        if (!check.holds()) {
            return finish(id, request, "blocked", precheck,
                    receipt("blocked", "没有执行：" + check.detail(), check.observed(), null));
        }
        String error = null;
        boolean maybeDone = false;
        try {
            Operations.execute(request.op(), params, lab);
        } catch (LabOperator.LabException e) {
            error = e.getMessage();
            maybeDone = e.maybeDone();
        }
        Operations.Outcome after;
        try {
            after = Operations.readBack(request.op(), params, lab);
        } catch (LabOperator.LabException e) {
            log.atWarn().addKeyValue("action_id", request.actionId()).log("Cannot read the lab back: {}", e.getMessage());
            return finish(id, request, "unknown", precheck, receipt("unknown", (error == null ? "已执行" : "执行出错（"
                    + error + "）") + "，但之后读不到靶场的状态，不能确认是否生效", Map.of(), error));
        }
        String status = after.status();
        String summary = after.summary();
        if (error != null && status.equals("executed")) {
            summary = "执行时报错（" + error + "），但读回显示已生效：" + summary;
        } else if (error != null) {
            status = maybeDone ? "unknown" : "failed";
            summary = "执行失败：" + error + "；" + summary;
        }
        return finish(id, request, status, precheck, receipt(status, summary, after.observed(), error));
    }

    public List<RemediationView> forCase(UUID caseId) {
        return store.forCase(caseId);
    }

    private void accept(UUID id, Request request, Map<String, Object> params, String digest) {
        CaseView current = cases.find(request.caseId()).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "CASE_NOT_FOUND", "No case " + request.caseId()));
        if (!CaseStatus.of(current.status()).active()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "CASE_CLOSED", "The case is resolved");
        }
        if (request.incidentId() != null && !current.incidentIds().contains(request.incidentId())) {
            throw invalid("incident " + request.incidentId() + " is not in this case");
        }
        if (current.stateVersion() != request.expectedCaseVersion()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "STALE_VERSION", "The case changed after the card was proposed "
                    + "(state version " + current.stateVersion() + ", not " + request.expectedCaseVersion() + ")");
        }
        store.insert(id, request.actionId(), request.caseId(), request.incidentId(), request.op(), params, digest,
                request.runId(), request.sessionId(), request.snapshotDigest());
        cases.advance(request.caseId(), CaseStatus.MITIGATING, null, null);
        cases.record(request.caseId(), "REMEDIATION_ACCEPTED", Map.of("remediation_id", id, "action_id",
                request.actionId(), "op", request.op(), "params", params));
    }

    private RemediationView finish(UUID id, Request request, String status, Map<String, Object> precheck,
            Map<String, Object> receipt) {
        tx.executeWithoutResult(s -> {
            if (store.finish(id, status, precheck, receipt) != 1) {
                return;
            }
            cases.record(request.caseId(), "REMEDIATION_" + status.toUpperCase(java.util.Locale.ROOT),
                    Map.of("remediation_id", id, "summary", receipt.get("summary")));
            switch (status) {
                case "executed" -> {
                    store.staleOpen(request.caseId());
                    Instant now = Instant.now();
                    store.insertVerification(Ids.newId(), request.caseId(), id, request.runId(),
                            Operations.criteria(request.op(), store.find(id).orElseThrow().params()),
                            now.plus(properties.verifyGrace()), now.plus(properties.verifyWindow()));
                    cases.advance(request.caseId(), CaseStatus.VERIFYING, null, null);
                }
                case "blocked", "failed" -> cases.advance(request.caseId(), CaseStatus.DIAGNOSING, null, null);
                default -> {
                    // unknown: the case stays mitigating until someone looks.
                }
            }
        });
        log.info("Remediation {} {}: {} (run {}, action {}, case {})", request.op(), status, id, request.runId(),
                request.actionId(), request.caseId());
        return store.find(id).orElseThrow();
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > MAX_ORIGIN;
    }

    private static Map<String, Object> payload(Request request, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("case_id", request.caseId().toString());
        if (request.incidentId() != null) {
            payload.put("incident_id", request.incidentId().toString());
        }
        payload.put("op", request.op());
        payload.put("params", params);
        return payload;
    }

    private static Map<String, Object> receipt(String status, String summary, Map<String, Object> observed,
            String error) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", status);
        receipt.put("summary", summary);
        receipt.put("observed", observed);
        if (error != null) {
            receipt.put("error", error);
        }
        receipt.put("at", Instant.now().toString());
        return receipt;
    }

    private static ApiProblem invalid(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }
}
