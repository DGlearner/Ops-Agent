package io.github.dglearner.opsagent.core.incident;

import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.alerting.AlertStored;
import io.github.dglearner.opsagent.core.catalog.Catalog;
import io.github.dglearner.opsagent.core.catalog.Topology;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Puts every new alert into an incident (see {@link Correlation}). Runs after the alert's transaction committed, in
 * a transaction of its own; Modulith's registry delivers the event again if this fails or the process dies, and an
 * alert already linked is skipped, so each alert lands in exactly one incident.
 */
@Component
class IncidentCorrelator {

    private static final Logger log = LoggerFactory.getLogger(IncidentCorrelator.class);
    static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final IncidentMapper mapper;
    private final Catalog catalog;
    private final Duration gap;

    IncidentCorrelator(IncidentMapper mapper, Catalog catalog,
            @Value("${ops.incident.correlation-gap:15m}") Duration gap) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.gap = gap;
    }

    @ApplicationModuleListener
    void on(AlertStored alert) {
        mapper.lockCorrelation();
        if (mapper.linked(alert.alertId())) {
            return;
        }
        String subject = Correlation.subject(alert);
        Topology topology = catalog.topology();
        List<Correlation.Candidate> candidates = mapper.selectList(Incidents.active(alert.createdAt(), gap)).stream()
                .map(r -> new Correlation.Candidate(r.getId(), Set.copyOf(strings(r.getSubjects())),
                        r.getOpenedAt().toInstant(), r.getLastAlertAt().toInstant()))
                .toList();
        Optional<UUID> chosen = Correlation.choose(subject, alert.createdAt(), candidates, topology, gap);
        UUID incidentId = chosen.map(id -> attach(id, alert, subject)).orElseGet(() -> open(alert, subject));
        mapper.link(alert.alertId(), incidentId);
        event(incidentId, "ALERT_ATTACHED", Map.of("alert_id", alert.alertId(), "rule_id", alert.ruleId(),
                "service", String.valueOf(alert.service()), "subject", subject, "summary", alert.summary()));
    }

    private UUID open(AlertStored alert, String subject) {
        IncidentRecord incident = new IncidentRecord();
        incident.setId(Ids.newId());
        incident.setTitle(alert.summary());
        incident.setStatus(IncidentStatus.OPEN.wire());
        incident.setSeverity(alert.severity().name().toLowerCase(Locale.ROOT));
        incident.setServices(OpsJson.toJson(alert.service() == null ? List.of() : List.of(alert.service())));
        incident.setSubjects(OpsJson.toJson(List.of(subject)));
        incident.setAlertCount(1);
        incident.setOpenedAt(alert.createdAt().atOffset(ZoneOffset.UTC));
        incident.setLastAlertAt(alert.createdAt().atOffset(ZoneOffset.UTC));
        mapper.insertIncident(incident);
        event(incident.getId(), "OPENED", Map.of("title", alert.summary(), "subject", subject));
        log.atInfo().addKeyValue("incident_id", incident.getId()).addKeyValue("subject", subject)
                .log("Incident opened: {}", alert.summary());
        return incident.getId();
    }

    private UUID attach(UUID incidentId, AlertStored alert, String subject) {
        IncidentRecord incident = mapper.selectById(incidentId);
        Severity severity = Severity.max(Severity.valueOf(incident.getSeverity().toUpperCase(Locale.ROOT)),
                alert.severity());
        Set<String> services = new LinkedHashSet<>(strings(incident.getServices()));
        if (alert.service() != null) {
            services.add(alert.service());
        }
        Set<String> subjects = new LinkedHashSet<>(strings(incident.getSubjects()));
        subjects.add(subject);
        mapper.attach(incidentId, severity.name().toLowerCase(Locale.ROOT), OpsJson.toJson(List.copyOf(services)),
                OpsJson.toJson(List.copyOf(subjects)), alert.createdAt().atOffset(ZoneOffset.UTC));
        return incidentId;
    }

    private void event(UUID incidentId, String type, Map<String, Object> payload) {
        mapper.insertEvent(Ids.newId(), incidentId, type, "SYSTEM", null, null,
                OpsJson.toJson(new LinkedHashMap<>(payload)));
    }

    static List<String> strings(String json) {
        return json == null ? new ArrayList<>() : OpsJson.mapper().readValue(json, STRINGS);
    }
}
