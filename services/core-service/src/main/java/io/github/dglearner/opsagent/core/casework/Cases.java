package io.github.dglearner.opsagent.core.casework;

import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.core.incident.IncidentView;
import io.github.dglearner.opsagent.core.incident.Incidents;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The casework module's API (docs/agent-design.md §2, §13.1). Sources and the current state are entries, and who may
 * write what is checked here: a fact needs something observed behind it, a decision or a constraint only comes from
 * the user, a hypothesis is only supported or refuted with evidence. Whoever calls cannot talk its way past that.
 */
@Service
public class Cases {

    public record NewEntry(String kind, String text, String status, String sourceType, String sourceRef,
            List<Map<String, Object>> evidence, Map<String, Object> data, UUID supersedes, String runId) {
    }

    static final int MAX_RUN_ID = 64;
    static final int MAX_TEXT = 500;
    static final int MAX_EVIDENCE = 10;
    static final int MAX_DATA = 4000;
    static final Set<String> ACTORS = Set.of("user", "agent", "system");
    // Which sources may write each kind of entry.
    private static final Map<String, Set<String>> SOURCES = Map.of(
            "statement", Set.of("user"),
            "observation", Set.of("observation", "receipt", "verification"),
            "fact", Set.of("observation", "receipt", "verification"),
            "hypothesis", Set.of("model", "rule", "analyst", "user"),
            "constraint", Set.of("user"),
            "decision", Set.of("user"),
            "note", Set.of("user", "observation", "rule", "analyst", "model", "receipt", "verification", "system"));
    private static final Map<String, Set<String>> STATUSES = Map.of(
            "hypothesis", Set.of("candidate", "supported", "refuted", "unverifiable"),
            "constraint", Set.of("active", "superseded"));
    // What an action is decided on: a change in these makes a proposed card stale (state_version).
    private static final Set<String> STATE = Set.of("fact", "constraint", "decision");

    private final CaseStore store;
    private final Incidents incidents;

    Cases(CaseStore store, Incidents incidents) {
        this.store = store;
        this.incidents = incidents;
    }

    /** The case these incidents are being handled in, or a new one; 409 CASE_CONFLICT if they are in two. */
    @Transactional
    public Opened open(List<UUID> incidentIds, String title, String goal, String actor) {
        if (incidentIds == null || incidentIds.isEmpty() || incidentIds.size() > 10) {
            throw invalid("incident_ids must list 1 to 10 incidents");
        }
        List<IncidentView> found = new ArrayList<>();
        for (UUID id : incidentIds) {
            found.add(incidents.find(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                    "INCIDENT_NOT_FOUND", "No incident " + id)));
        }
        List<UUID> open = store.openCasesOf(incidentIds);
        if (open.size() > 1) {
            throw new ApiProblem(HttpStatus.CONFLICT, "CASE_CONFLICT",
                    "These incidents are in different cases: " + open);
        }
        if (open.size() == 1) {
            return new Opened(view(open.getFirst()), false);
        }
        UUID id = Ids.newId();
        store.insert(id, limit(title == null || title.isBlank() ? found.getFirst().title() : title, 200), "open",
                goal == null ? null : limit(goal, MAX_TEXT));
        try {
            for (UUID incidentId : incidentIds) {
                store.link(id, incidentId);
            }
        } catch (DuplicateKeyException e) {
            // Someone opened a case for one of them meanwhile; this transaction rolls back and the caller asks again.
            throw new ApiProblem(HttpStatus.CONFLICT, "CASE_CONFLICT", "A case for these incidents was just opened");
        }
        store.event(Ids.newId(), id, "OPENED", actor(actor), Map.of("incident_ids", incidentIds));
        return new Opened(view(id), true);
    }

    public record Opened(CaseView view, boolean created) {
    }

    public Optional<CaseView> find(UUID caseId) {
        return store.find(caseId).map(row -> view(row.id()));
    }

    public List<CaseView> list(UUID incidentId, String status, int limit) {
        if (status != null) {
            status(status);
        }
        return store.list(incidentId, status, limit).stream().map(row -> view(row.id())).toList();
    }

    @Transactional
    public CaseView addEntry(UUID caseId, NewEntry entry, Integer expectedVersion) {
        CaseStore.Row row = writable(caseId, expectedVersion);
        String kind = entry.kind();
        if (kind == null || !SOURCES.containsKey(kind)) {
            throw invalid("kind must be one of " + SOURCES.keySet());
        }
        if (entry.sourceType() == null || !SOURCES.get(kind).contains(entry.sourceType())) {
            throw invalid("a " + kind + " can only come from " + SOURCES.get(kind) + ", not " + entry.sourceType());
        }
        String text = entry.text() == null ? "" : entry.text().strip();
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw invalid("text must be 1 to " + MAX_TEXT + " characters");
        }
        List<Map<String, Object>> evidence = entry.evidence() == null ? List.of() : entry.evidence();
        Map<String, Object> data = entry.data() == null ? Map.of() : entry.data();
        if (evidence.size() > MAX_EVIDENCE || OpsJson.toJson(data).length() > MAX_DATA) {
            throw invalid("at most " + MAX_EVIDENCE + " pieces of evidence and " + MAX_DATA + " characters of data");
        }
        String status = entry.status() == null ? defaultStatus(kind) : entry.status();
        if (STATUSES.containsKey(kind) ? !STATUSES.get(kind).contains(status) : !status.equals("active")) {
            throw invalid("a " + kind + " cannot be " + status);
        }
        switch (kind) {
            case "observation", "fact" -> {
                if (evidence.isEmpty() && data.isEmpty()) {
                    throw invalid("a " + kind + " needs the evidence or the data it was observed in");
                }
            }
            case "hypothesis" -> {
                if ((status.equals("supported") || status.equals("refuted")) && evidence.isEmpty()) {
                    throw invalid("a hypothesis is " + status + " only with evidence");
                }
            }
            case "statement", "constraint" -> {
                if (!(data.get("quote") instanceof String quote) || quote.isBlank()) {
                    throw invalid("a " + kind + " keeps the user's own words in data.quote");
                }
            }
            case "decision" -> {
                if (entry.sourceRef() == null || entry.sourceRef().isBlank()) {
                    throw invalid("a decision names the questionnaire or card it was made on (source_ref)");
                }
            }
            default -> {
            }
        }
        if (entry.supersedes() != null) {
            CaseView.Entry old = store.entry(caseId, entry.supersedes()).orElseThrow(() ->
                    invalid("no entry " + entry.supersedes() + " in this case"));
            if (!old.kind().equals(kind) || old.status().equals("superseded")) {
                throw invalid("an entry supersedes a current entry of the same kind");
            }
        }
        if (entry.runId() != null && entry.runId().length() > MAX_RUN_ID) {
            throw invalid("run_id is at most " + MAX_RUN_ID + " characters");
        }
        cas(row, STATE.contains(kind));
        UUID id = Ids.newId();
        store.insertEntry(id, caseId, kind, status, text, entry.sourceType(),
                entry.sourceRef() == null ? null : limit(entry.sourceRef(), 100), evidence, data, entry.supersedes(),
                entry.runId());
        if (entry.supersedes() != null) {
            store.supersede(entry.supersedes(), id);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entry_id", id);
        payload.put("kind", kind);
        payload.put("source_type", entry.sourceType());
        store.event(Ids.newId(), caseId, "ENTRY_ADDED", entry.sourceType().equals("user") ? "user" : "agent",
                payload);
        return view(caseId);
    }

    /** A hypothesis supported, refuted or found unverifiable; a constraint lifted. */
    @Transactional
    public CaseView entryStatus(UUID caseId, UUID entryId, String status, List<Map<String, Object>> evidence,
            Integer expectedVersion) {
        CaseStore.Row row = writable(caseId, expectedVersion);
        CaseView.Entry entry = store.entry(caseId, entryId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND,
                "ENTRY_NOT_FOUND", "No entry " + entryId + " in case " + caseId));
        Set<String> allowed = STATUSES.get(entry.kind());
        if (allowed == null || status == null || !allowed.contains(status)) {
            throw invalid("a " + entry.kind() + " cannot be set to " + status);
        }
        List<Map<String, Object>> more = evidence == null ? List.of() : evidence;
        if ((status.equals("supported") || status.equals("refuted")) && entry.evidence().isEmpty() && more.isEmpty()) {
            throw invalid("a hypothesis is " + status + " only with evidence");
        }
        cas(row, STATE.contains(entry.kind()));
        store.entryStatus(entryId, status, more);
        store.event(Ids.newId(), caseId, "ENTRY_STATUS", "agent", Map.of("entry_id", entryId, "from",
                entry.status(), "to", status));
        return view(caseId);
    }

    @Transactional
    public CaseView addIncident(UUID caseId, UUID incidentId, Integer expectedVersion) {
        CaseStore.Row row = writable(caseId, expectedVersion);
        if (incidents.find(incidentId).isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No incident " + incidentId);
        }
        if (store.incidents(caseId).contains(incidentId)) {
            return view(caseId);
        }
        if (!store.openCasesOf(List.of(incidentId)).isEmpty()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "CASE_CONFLICT", "The incident is in another open case");
        }
        cas(row, true);
        store.link(caseId, incidentId);
        store.event(Ids.newId(), caseId, "INCIDENT_ADDED", "user", Map.of("incident_id", incidentId));
        return view(caseId);
    }

    /** 409 if the lifecycle does not allow it, or the case is not at expectedVersion. Resolving is the user's. */
    @Transactional
    public CaseView transition(UUID caseId, String toStatus, String actor, String note, Integer expectedVersion) {
        CaseStore.Row row = require(caseId);
        if (expectedVersion == null) {
            throw invalid("expected_version is required");
        }
        return move(row, status(toStatus), actor(actor), note, expectedVersion);
    }

    /**
     * For the system's own steps (an action run, a verification done): moves the case on from where it is now if the
     * lifecycle allows, else leaves it. Returns whether it moved.
     */
    @Transactional
    public boolean advance(UUID caseId, CaseStatus to, String note, Map<String, Object> payload) {
        CaseStore.Row row = require(caseId);
        CaseStatus from = CaseStatus.of(row.status());
        if (from == to || !from.canMoveTo(to) || to == CaseStatus.RESOLVED) {
            return false;
        }
        move(row, to, "system", note, row.version());
        if (payload != null && !payload.isEmpty()) {
            record(caseId, "SYSTEM_STEP", payload);
        }
        return true;
    }

    /** An event on the case's timeline that changes nothing else (an action run, a verification's verdict). */
    public void record(UUID caseId, String type, Map<String, Object> payload) {
        store.event(Ids.newId(), caseId, type, "system", payload);
    }

    private CaseView move(CaseStore.Row row, CaseStatus to, String actor, String note, int expectedVersion) {
        CaseStatus from = CaseStatus.of(row.status());
        if (row.version() != expectedVersion) {
            throw stale(row);
        }
        if (!from.canMoveTo(to)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION",
                    "A case cannot go from " + from.wire() + " to " + to.wire());
        }
        if (to == CaseStatus.RESOLVED && !actor.equals("user")) {
            throw new ApiProblem(HttpStatus.CONFLICT, "SIGN_OFF_REQUIRED",
                    "Only a person signs a case off as resolved");
        }
        if (store.move(row.id(), to.wire(), expectedVersion) != 1) {
            throw stale(store.find(row.id()).orElse(row));
        }
        if (to == CaseStatus.RESOLVED) {
            store.close(row.id());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.wire());
        payload.put("to", to.wire());
        if (note != null && !note.isBlank()) {
            payload.put("note", limit(note, MAX_TEXT));
        }
        store.event(Ids.newId(), row.id(), "STATUS_CHANGED", actor, payload);
        return view(row.id());
    }

    private CaseStore.Row writable(UUID caseId, Integer expectedVersion) {
        CaseStore.Row row = require(caseId);
        if (!CaseStatus.of(row.status()).active()) {
            throw new ApiProblem(HttpStatus.CONFLICT, "CASE_CLOSED", "The case is resolved");
        }
        if (expectedVersion == null) {
            throw invalid("expected_version is required");
        }
        if (row.version() != expectedVersion) {
            throw stale(row);
        }
        return row;
    }

    private void cas(CaseStore.Row row, boolean state) {
        if (store.bump(row.id(), row.version(), state) != 1) {
            throw stale(store.find(row.id()).orElse(row));
        }
    }

    private CaseStore.Row require(UUID caseId) {
        return store.find(caseId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND",
                "No case " + caseId));
    }

    private CaseView view(UUID caseId) {
        CaseStore.Row row = require(caseId);
        return new CaseView(row.id(), row.title(), row.status(), row.goal(), row.runbook(), row.version(),
                row.stateVersion(), store.incidents(caseId), store.entries(caseId), store.events(caseId),
                row.createdAt(), row.updatedAt(), row.resolvedAt());
    }

    private static String defaultStatus(String kind) {
        return switch (kind) {
            case "hypothesis" -> "candidate";
            default -> "active";
        };
    }

    private static CaseStatus status(String value) {
        try {
            return CaseStatus.of(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("status must be one of open, diagnosing, mitigating, verifying, monitoring, resolved, "
                    + "handed_over");
        }
    }

    private static String actor(String actor) {
        if (actor == null || !ACTORS.contains(actor)) {
            throw invalid("actor must be one of " + ACTORS);
        }
        return actor;
    }

    private static ApiProblem stale(CaseStore.Row row) {
        return new ApiProblem(HttpStatus.CONFLICT, "STALE_VERSION",
                "The case is at version " + row.version() + "; read it again before deciding");
    }

    private static ApiProblem invalid(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
