package io.github.dglearner.opsagent.core.agentops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.dglearner.opsagent.contracts.AgentResult;
import io.github.dglearner.opsagent.contracts.AgentTask;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.core.alerting.AlertView;
import io.github.dglearner.opsagent.core.catalog.Catalog;
import io.github.dglearner.opsagent.core.incident.Incidents;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/**
 * The agentops module's API: asking the agent to analyse an incident, and its answers (docs/project-design.md §7.6).
 * The agent only reads, through the read-only API; what it concludes is checked here before anyone sees it.
 */
@Service
public class Analyses {

    static final String SYSTEM = "system";
    static final String USER = "user";
    static final int MAX_NEXT_STEPS = 10;
    static final int MAX_SUMMARY = 4000;

    private static final TypeReference<List<AgentResult.Hypothesis>> HYPOTHESES = new TypeReference<>() {
    };
    private static final TypeReference<List<AgentResult.Evidence>> EVIDENCE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final AnalysisMapper mapper;
    private final Incidents incidents;
    private final Catalog catalog;
    private final AgentProperties properties;
    private final ApplicationEventPublisher events;

    Analyses(AnalysisMapper mapper, Incidents incidents, Catalog catalog, AgentProperties properties,
            ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.incidents = incidents;
        this.catalog = catalog;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Asks the agent to analyse the incident. If an analysis of it is still waiting for the agent, that one is
     * returned instead: one at a time per incident.
     */
    @Transactional
    public AnalysisView request(UUID incidentId, String requestedBy) {
        if (incidents.find(incidentId).isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No incident " + incidentId);
        }
        UUID id = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = now.plus(properties.timeout());
        if (mapper.insertPending(id, incidentId, requestedBy, utc(now), utc(deadline)) == 1) {
            events.publishEvent(new AnalysisRequested(new AgentTask(id, AgentTask.INCIDENT_ANALYSIS, incidentId,
                    requestedBy, now, deadline, properties.language())));
        }
        return view(rows(incidentId).getFirst());
    }

    /** The incident's analyses, newest first. */
    public List<AnalysisView> forIncident(UUID incidentId) {
        if (incidents.find(incidentId).isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No incident " + incidentId);
        }
        return rows(incidentId).stream().map(Analyses::view).toList();
    }

    public Optional<AnalysisView> find(UUID analysisId) {
        return Optional.ofNullable(mapper.selectById(analysisId)).map(Analyses::view);
    }

    /**
     * Analyses an incident that went quiet, unless it was analysed after its latest alert, is being analysed, or had
     * its share of automatic analyses.
     */
    @Transactional
    void requestIfDue(Incidents.Settled incident) {
        List<AnalysisRecord> previous = rows(incident.incidentId());
        if (previous.stream().anyMatch(a -> a.getStatus().equals("pending"))) {
            return;
        }
        if (!previous.isEmpty()
                && !incident.lastAlertArrivedAt().isAfter(previous.getFirst().getRequestedAt().toInstant())) {
            return;
        }
        if (previous.stream().filter(a -> a.getRequestedBy().equals(SYSTEM)).count() >= properties.maxAutoAnalyses()) {
            return;
        }
        request(incident.incidentId(), SYSTEM);
    }

    /** Analyses still waiting past their deadline are marked timed out; a late answer is still stored. */
    int expire() {
        return mapper.expirePending();
    }

    /**
     * Stores the agent's answer after checking its evidence against the incident. False if no analysis is waiting
     * for it: unknown, or answered already.
     */
    @Transactional
    boolean complete(AgentResult result) {
        if (!result.status().equals(AgentResult.COMPLETED) && !result.status().equals(AgentResult.FAILED)) {
            throw new IllegalArgumentException("Unknown agent result status " + result.status());
        }
        AnalysisRecord analysis = mapper.selectById(result.taskId());
        if (analysis == null || !analysis.getIncidentId().equals(result.incidentId())) {
            return false;
        }
        EvidenceCheck.Checked checked = EvidenceCheck.check(result.hypotheses(), known(result.incidentId()));
        AnalysisRecord row = new AnalysisRecord();
        row.setId(result.taskId());
        row.setIncidentId(result.incidentId());
        row.setStatus(result.status());
        row.setCompletedAt(utc(result.completedAt() != null ? result.completedAt() : Instant.now()));
        row.setSummary(result.summary() == null || result.summary().length() <= MAX_SUMMARY ? result.summary()
                : result.summary().substring(0, MAX_SUMMARY));
        row.setUncertain(result.uncertain() || checked.unsupported());
        row.setHypotheses(OpsJson.toJson(checked.hypotheses()));
        row.setNextSteps(OpsJson.toJson(result.nextSteps().stream().limit(MAX_NEXT_STEPS).toList()));
        row.setRejectedEvidence(OpsJson.toJson(checked.rejected()));
        row.setModel(result.model());
        row.setToolCalls(result.toolCalls());
        row.setDurationMs(result.durationMs());
        row.setError(result.error());
        return mapper.complete(row) == 1;
    }

    private EvidenceCheck.Known known(UUID incidentId) {
        Set<String> alertIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (AlertView alert : incidents.alerts(incidentId)) {
            alertIds.add(lower(alert.alertId()));
            alert.sampleEventIds().forEach(e -> eventIds.add(lower(e)));
            alert.samples().forEach(s -> eventIds.add(lower(s.eventId())));
        }
        return new EvidenceCheck.Known(incidentId, alertIds, eventIds, catalog.topology().kinds().keySet());
    }

    private List<AnalysisRecord> rows(UUID incidentId) {
        return mapper.selectList(Wrappers.<AnalysisRecord>lambdaQuery()
                .eq(AnalysisRecord::getIncidentId, incidentId)
                .orderByDesc(AnalysisRecord::getRequestedAt).orderByDesc(AnalysisRecord::getId));
    }

    private static AnalysisView view(AnalysisRecord row) {
        return new AnalysisView(row.getId(), row.getIncidentId(), row.getStatus(), row.getRequestedBy(),
                instant(row.getRequestedAt()), instant(row.getCompletedAt()), row.getSummary(), row.getUncertain(),
                OpsJson.mapper().readValue(row.getHypotheses(), HYPOTHESES),
                OpsJson.mapper().readValue(row.getNextSteps(), STRINGS),
                OpsJson.mapper().readValue(row.getRejectedEvidence(), EVIDENCE), row.getModel(), row.getToolCalls(),
                row.getDurationMs(), row.getError());
    }

    private static String lower(UUID id) {
        return id.toString().toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
