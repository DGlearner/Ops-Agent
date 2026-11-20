package io.github.dglearner.opsagent.core.agentops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.dglearner.opsagent.contracts.AgentResult;
import io.github.dglearner.opsagent.contracts.AgentTask;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.core.alerting.AlertView;
import io.github.dglearner.opsagent.core.catalog.Catalog;
import io.github.dglearner.opsagent.core.incident.Incidents;
import io.github.dglearner.opsagent.core.logs.Logs;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    /** How far around an incident the agent may cite log lines it found by searching (its tools look this far). */
    static final Duration LOG_EVIDENCE_MARGIN = Duration.ofMinutes(10);

    private final AnalysisMapper mapper;
    private final Incidents incidents;
    private final Catalog catalog;
    private final Logs logs;
    private final AgentProperties properties;
    private final ApplicationEventPublisher events;

    Analyses(AnalysisMapper mapper, Incidents incidents, Catalog catalog, Logs logs, AgentProperties properties,
            ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.incidents = incidents;
        this.catalog = catalog;
        this.logs = logs;
        this.properties = properties;
        this.events = events;
    }

    /**
     * Asks the agent to analyse the incident. If an analysis of it is still waiting for the agent, that one is
     * returned instead: one at a time per incident.
     */
    @Transactional
    public AnalysisView request(UUID incidentId, String requestedBy) {
        mapper.lockIncident(incidentId);
        if (incidents.find(incidentId).isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No incident " + incidentId);
        }
        UUID id = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant deadline = now.plus(properties.timeout());
        if (mapper.insertPending(id, incidentId, requestedBy, utc(now), utc(deadline)) == 1) {
            events.publishEvent(new AnalysisRequested(new AgentTask(id, AgentTask.INCIDENT_ANALYSIS, incidentId,
                    requestedBy, now, deadline, properties.language())));
            return find(id).orElseThrow();
        }
        return rows(incidentId).stream().filter(a -> a.getStatus().equals("pending"))
                .findFirst().map(Analyses::view).orElseGet(() -> view(rows(incidentId).getFirst()));
    }

    /**
     * System orchestration of a read-only investigation, not a user's forced reanalysis. A terminal attempt for
     * this evidence is returned too: polling, a second model turn or another chat must not become a retry loop.
     * New alert arrival (including a late alert with an old event time) permits a new automatic attempt.
     */
    @Transactional
    public Optional<AnalysisView> ensure(UUID incidentId) {
        mapper.lockIncident(incidentId);
        var incident = incidents.find(incidentId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "INCIDENT_NOT_FOUND", "No incident " + incidentId));
        List<AnalysisRecord> previous = rows(incidentId);
        var pending = previous.stream().filter(a -> a.getStatus().equals("pending")).findFirst();
        if (pending.isPresent()) {
            return pending.map(Analyses::view);
        }
        var arrived = incidents.timeline(incidentId).stream()
                .filter(e -> Set.of("OPENED", "ALERT_ATTACHED").contains(e.type()))
                .map(e -> e.at()).max(Instant::compareTo);
        if (arrived.isEmpty() || incident.alertCount() == 0) {
            return Optional.empty();
        }
        if (!previous.isEmpty() && !arrived.get().isAfter(previous.getFirst().getRequestedAt().toInstant())) {
            return Optional.of(view(previous.getFirst()));
        }
        if (!Set.of("open", "acknowledged", "investigating", "mitigated").contains(incident.status())
                || previous.stream().filter(a -> a.getRequestedBy().equals(SYSTEM)).count()
                        >= properties.maxAutoAnalyses()) {
            return Optional.empty();
        }
        return Optional.of(request(incidentId, SYSTEM));
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
        ensure(incident.incidentId());
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
        EvidenceCheck.Checked checked = EvidenceCheck.check(result.hypotheses(), known(result));
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

    /**
     * What the incident has: its alerts and their samples, the catalog, and the stored log lines around it that the
     * result cites (the agent finds those by searching the logs).
     */
    private EvidenceCheck.Known known(AgentResult result) {
        UUID incidentId = result.incidentId();
        Set<String> alertIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        for (AlertView alert : incidents.alerts(incidentId)) {
            alertIds.add(lower(alert.alertId()));
            alert.sampleEventIds().forEach(e -> eventIds.add(lower(e)));
            alert.samples().forEach(s -> eventIds.add(lower(s.eventId())));
        }
        List<UUID> cited = new ArrayList<>();
        for (AgentResult.Hypothesis h : result.hypotheses()) {
            for (AgentResult.Evidence e : h.evidence()) {
                if ("event".equals(e.kind()) && e.id() != null && !eventIds.contains(e.id().strip().toLowerCase(
                        Locale.ROOT))) {
                    uuid(e.id()).ifPresent(cited::add);
                }
            }
        }
        if (!cited.isEmpty()) {
            incidents.find(incidentId).ifPresent(i -> logs.existing(cited,
                    i.openedAt().minus(LOG_EVIDENCE_MARGIN), i.lastAlertAt().plus(LOG_EVIDENCE_MARGIN))
                    .forEach(id -> eventIds.add(lower(id))));
        }
        return new EvidenceCheck.Known(incidentId, alertIds, eventIds, catalog.topology().kinds().keySet());
    }

    private static Optional<UUID> uuid(String id) {
        try {
            return Optional.of(UUID.fromString(id.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
