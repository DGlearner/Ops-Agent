package io.github.dglearner.opsagent.core.alerting;

import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/alerts} (docs/project-design.md §7.3). */
@RestController
@RequestMapping("/api/v1/alerts")
class AlertController {

    private static final int MAX_LIMIT = 200;

    private final Alerts alerts;

    AlertController(Alerts alerts) {
        this.alerts = alerts;
    }

    /** Newest first, filtered by severity, service, rule and creation time; {@code from} inclusive, {@code to} not. */
    @GetMapping
    AlertPage list(@RequestParam(required = false) String severity, @RequestParam(required = false) String service,
            @RequestParam(name = "rule_id", required = false) String ruleId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return alerts.list(new AlertFilter(severity(severity), service, ruleId, from, to), limit, cursor);
    }

    @GetMapping("/{alertId}")
    AlertView get(@PathVariable UUID alertId) {
        return alerts.find(alertId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND",
                "No alert " + alertId));
    }

    private static Severity severity(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Severity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "severity must be one of info, low, medium, high, critical");
        }
    }
}
