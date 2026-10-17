package io.github.dglearner.opsagent.core.incident;

import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.alerting.AlertView;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/incidents} (docs/project-design.md §7.3). */
@RestController
@RequestMapping("/api/v1/incidents")
class IncidentController {

    record TransitionRequest(String toStatus, String note) {
    }

    private final Incidents incidents;

    IncidentController(Incidents incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    IncidentPage list(@RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) String service, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 200) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "limit must be between 1 and 200");
        }
        return incidents.list(new Incidents.Filter(status == null ? null : status(status),
                severity == null ? null : severity(severity), service, from, to), limit, cursor);
    }

    @GetMapping("/{incidentId}")
    IncidentView get(@PathVariable UUID incidentId) {
        return incidents.find(incidentId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "INCIDENT_NOT_FOUND", "No incident " + incidentId));
    }

    @GetMapping("/{incidentId}/alerts")
    List<AlertView> alerts(@PathVariable UUID incidentId) {
        return incidents.alerts(incidentId);
    }

    @GetMapping("/{incidentId}/timeline")
    List<TimelineEntry> timeline(@PathVariable UUID incidentId) {
        return incidents.timeline(incidentId);
    }

    @PostMapping("/{incidentId}/transitions")
    IncidentView transition(@PathVariable UUID incidentId, @RequestBody TransitionRequest request) {
        if (request.toStatus() == null) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "to_status is required");
        }
        return incidents.transition(incidentId, status(request.toStatus()), request.note());
    }

    private static IncidentStatus status(String value) {
        try {
            return IncidentStatus.of(value);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "status must be one of open, acknowledged, investigating, mitigated, resolved, closed");
        }
    }

    private static Severity severity(String value) {
        try {
            return Severity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "severity must be one of info, low, medium, high, critical");
        }
    }
}
