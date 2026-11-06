package io.github.dglearner.opsagent.core.casework;

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

/** {@code /api/v1/cases} (docs/agent-design.md §13.1). */
@RestController
@RequestMapping("/api/v1/cases")
class CaseController {

    record OpenRequest(List<UUID> incidentIds, String title, String goal, String actor) {
    }

    record EntryRequest(String kind, String text, String status, String sourceType, String sourceRef,
            List<Map<String, Object>> evidence, Map<String, Object> data, UUID supersedes, String runId,
            Integer expectedVersion) {
    }

    record StatusRequest(String status, List<Map<String, Object>> evidence, Integer expectedVersion) {
    }

    record IncidentRequest(UUID incidentId, Integer expectedVersion) {
    }

    record TransitionRequest(String toStatus, String actor, String note, Integer expectedVersion) {
    }

    record CasePage(List<CaseView> items) {
    }

    private final Cases cases;

    CaseController(Cases cases) {
        this.cases = cases;
    }

    @PostMapping
    ResponseEntity<CaseView> open(@RequestBody OpenRequest request) {
        Cases.Opened opened = cases.open(request.incidentIds(), request.title(), request.goal(),
                request.actor() == null ? "agent" : request.actor());
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK).body(opened.view());
    }

    @GetMapping
    CasePage list(@RequestParam(name = "incident_id", required = false) UUID incidentId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "limit must be between 1 and 100");
        }
        return new CasePage(cases.list(incidentId, status, limit));
    }

    @GetMapping("/{caseId}")
    CaseView get(@PathVariable UUID caseId) {
        return cases.find(caseId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND",
                "No case " + caseId));
    }

    @PostMapping("/{caseId}/entries")
    ResponseEntity<CaseView> entry(@PathVariable UUID caseId, @RequestBody EntryRequest request) {
        CaseView view = cases.addEntry(caseId, new Cases.NewEntry(request.kind(), request.text(), request.status(),
                request.sourceType(), request.sourceRef(), request.evidence(), request.data(), request.supersedes(),
                request.runId()),
                request.expectedVersion());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{caseId}/entries/{entryId}/status")
    CaseView entryStatus(@PathVariable UUID caseId, @PathVariable UUID entryId, @RequestBody StatusRequest request) {
        return cases.entryStatus(caseId, entryId, request.status(), request.evidence(), request.expectedVersion());
    }

    @PostMapping("/{caseId}/incidents")
    CaseView incident(@PathVariable UUID caseId, @RequestBody IncidentRequest request) {
        if (request.incidentId() == null) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "incident_id is required");
        }
        return cases.addIncident(caseId, request.incidentId(), request.expectedVersion());
    }

    @PostMapping("/{caseId}/transitions")
    CaseView transition(@PathVariable UUID caseId, @RequestBody TransitionRequest request) {
        return cases.transition(caseId, request.toStatus(), request.actor() == null ? "user" : request.actor(),
                request.note(), request.expectedVersion());
    }
}
