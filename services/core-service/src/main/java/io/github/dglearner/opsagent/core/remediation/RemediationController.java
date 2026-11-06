package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/remediations}, {@code /api/v1/verifications}, {@code /api/v1/lab} (docs/agent-design.md §13.2). */
@RestController
@RequestMapping("/api/v1")
class RemediationController {

    record RemediationRequest(String actionId, UUID caseId, UUID incidentId, String op, Map<String, Object> params,
            String payloadDigest, Integer expectedCaseVersion, String runId, String sessionId, String snapshotDigest) {
    }

    record ClaimRequest(String worker) {
    }

    record ResultRequest(Integer token, String outcome, Map<String, Object> result) {
    }

    record Items<T>(List<T> items) {
    }

    record Container(String name, String state) {
    }

    private final Remediations remediations;
    private final Verifications verifications;
    private final LabOperator lab;
    private final LabProperties properties;

    RemediationController(Remediations remediations, Verifications verifications, LabOperator lab,
            LabProperties properties) {
        this.remediations = remediations;
        this.verifications = verifications;
        this.lab = lab;
        this.properties = properties;
    }

    @PostMapping("/remediations")
    RemediationView execute(@RequestBody RemediationRequest r) {
        return remediations.execute(new Remediations.Request(r.actionId(), r.caseId(), r.incidentId(), r.op(),
                r.params(), r.payloadDigest(), r.expectedCaseVersion(), r.runId(), r.sessionId(), r.snapshotDigest()));
    }

    @GetMapping("/remediations")
    Items<RemediationView> remediations(@RequestParam("case_id") UUID caseId) {
        return new Items<>(remediations.forCase(caseId));
    }

    @GetMapping("/verifications")
    Items<VerificationView> verifications(@RequestParam("case_id") UUID caseId) {
        return new Items<>(verifications.forCase(caseId));
    }

    @PostMapping("/verifications/claim")
    ResponseEntity<VerificationView> claim(@RequestBody ClaimRequest request) {
        return verifications.claim(request.worker()).map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/verifications/{verificationId}/result")
    VerificationView result(@PathVariable UUID verificationId, @RequestBody ResultRequest request) {
        return verifications.complete(verificationId, request.token(), request.outcome(), request.result());
    }

    // Read-only probes of the lab, for the agent's runbooks.

    @GetMapping("/lab/containers/{name}")
    Container container(@PathVariable String name) {
        if (!properties.containers().containsKey(name)) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "CONTAINER_NOT_FOUND", "No container " + name + " in the lab");
        }
        return new Container(name, probe(() -> lab.containerState(name)));
    }

    @GetMapping("/lab/transactions")
    Items<LabOperator.Transaction> transactions(@RequestParam(name = "min_age_s", defaultValue = "5") long minAgeS) {
        return new Items<>(probe(() -> lab.transactions(minAgeS)));
    }

    @GetMapping("/lab/switches")
    Items<LabOperator.Switch> switches() {
        return new Items<>(probe(lab::switches));
    }

    private static <T> T probe(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (LabOperator.LabException e) {
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "LAB_UNAVAILABLE", e.getMessage());
        }
    }
}
