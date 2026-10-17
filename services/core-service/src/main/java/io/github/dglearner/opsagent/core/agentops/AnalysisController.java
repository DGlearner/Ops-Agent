package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/incidents/{id}/analyses} and {@code /api/v1/analyses/{id}} (docs/project-design.md §7.3). */
@RestController
@RequestMapping("/api/v1")
class AnalysisController {

    private final Analyses analyses;

    AnalysisController(Analyses analyses) {
        this.analyses = analyses;
    }

    /** 202: the analysis is pending; poll it until the agent answered. */
    @PostMapping("/incidents/{incidentId}/analyses")
    ResponseEntity<AnalysisView> request(@PathVariable UUID incidentId) {
        return ResponseEntity.accepted().body(analyses.request(incidentId, Analyses.USER));
    }

    @GetMapping("/incidents/{incidentId}/analyses")
    List<AnalysisView> forIncident(@PathVariable UUID incidentId) {
        return analyses.forIncident(incidentId);
    }

    @GetMapping("/analyses/{analysisId}")
    AnalysisView get(@PathVariable UUID analysisId) {
        return analyses.find(analysisId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "ANALYSIS_NOT_FOUND", "No analysis " + analysisId));
    }
}
