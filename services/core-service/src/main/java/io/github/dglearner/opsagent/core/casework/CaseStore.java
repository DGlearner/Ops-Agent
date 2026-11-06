package io.github.dglearner.opsagent.core.casework;

import io.github.dglearner.opsagent.contracts.OpsJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** The casework tables. Every change of a case is a compare-and-set on its version. */
@Component
class CaseStore {

    record Row(UUID id, String title, String status, String goal, String runbook, int version, int stateVersion,
            Instant createdAt, Instant updatedAt, Instant resolvedAt) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    CaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String title, String status, String goal) {
        jdbc.sql("INSERT INTO cases (id, title, status, goal) VALUES (?, ?, ?, ?)")
                .params(id, title, status, goal).update();
    }

    void link(UUID caseId, UUID incidentId) {
        jdbc.sql("INSERT INTO case_incidents (case_id, incident_id) VALUES (?, ?)").params(caseId, incidentId)
                .update();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("SELECT * FROM cases WHERE id = ?").param(id).query(CaseStore::row).optional();
    }

    List<Row> list(UUID incidentId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT c.* FROM cases c WHERE true");
        if (incidentId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM case_incidents ci WHERE ci.case_id = c.id AND ci.incident_id = :i)");
        }
        if (status != null) {
            sql.append(" AND c.status = :s");
        }
        sql.append(" ORDER BY c.updated_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", limit);
        if (incidentId != null) {
            spec = spec.param("i", incidentId);
        }
        if (status != null) {
            spec = spec.param("s", status);
        }
        return spec.query(CaseStore::row).list();
    }

    /** The cases still being handled that any of these incidents is in. */
    List<UUID> openCasesOf(List<UUID> incidentIds) {
        return jdbc.sql("SELECT DISTINCT case_id FROM case_incidents WHERE open AND incident_id IN (:ids)")
                .param("ids", incidentIds).query(UUID.class).list();
    }

    List<UUID> incidents(UUID caseId) {
        return jdbc.sql("SELECT incident_id FROM case_incidents WHERE case_id = ? ORDER BY added_at")
                .param(caseId).query(UUID.class).list();
    }

    /** 0 if the case is no longer at {@code version}. */
    int bump(UUID id, int version, boolean state) {
        return jdbc.sql("UPDATE cases SET version = version + 1, state_version = state_version + :s, updated_at = now() "
                + "WHERE id = :id AND version = :v").param("s", state ? 1 : 0).param("id", id).param("v", version)
                .update();
    }

    /** Moves the status on; 0 if the case is no longer at {@code version}. */
    int move(UUID id, String to, int version) {
        return jdbc.sql("""
                UPDATE cases SET status = :to, version = version + 1, state_version = state_version + 1,
                    updated_at = now(), resolved_at = CASE WHEN :to = 'resolved' THEN now() ELSE resolved_at END
                WHERE id = :id AND version = :v""").param("to", to).param("id", id).param("v", version).update();
    }

    void close(UUID caseId) {
        jdbc.sql("UPDATE case_incidents SET open = false WHERE case_id = ?").param(caseId).update();
    }

    void insertEntry(UUID id, UUID caseId, String kind, String status, String text, String sourceType,
            String sourceRef, List<Map<String, Object>> evidence, Map<String, Object> data, UUID supersedes,
            String runId) {
        jdbc.sql("""
                INSERT INTO case_entries (id, case_id, kind, status, text, source_type, source_ref, evidence, data,
                                          supersedes, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)""")
                .params(id, caseId, kind, status, text, sourceType, sourceRef, OpsJson.toJson(evidence),
                        OpsJson.toJson(data), supersedes, runId).update();
    }

    Optional<CaseView.Entry> entry(UUID caseId, UUID entryId) {
        return jdbc.sql("SELECT * FROM case_entries WHERE case_id = ? AND id = ?").params(caseId, entryId)
                .query(CaseStore::entry).optional();
    }

    void supersede(UUID entryId, UUID by) {
        jdbc.sql("UPDATE case_entries SET status = 'superseded', superseded_by = ?, updated_at = now() WHERE id = ?")
                .params(by, entryId).update();
    }

    void entryStatus(UUID entryId, String status, List<Map<String, Object>> evidence) {
        jdbc.sql("""
                UPDATE case_entries SET status = ?, evidence = evidence || CAST(? AS jsonb), updated_at = now()
                WHERE id = ?""").params(status, OpsJson.toJson(evidence), entryId).update();
    }

    List<CaseView.Entry> entries(UUID caseId) {
        return jdbc.sql("SELECT * FROM case_entries WHERE case_id = ? ORDER BY created_at, id").param(caseId)
                .query(CaseStore::entry).list();
    }

    void event(UUID id, UUID caseId, String type, String actor, Map<String, Object> payload) {
        jdbc.sql("INSERT INTO case_events (id, case_id, type, actor, payload) VALUES (?, ?, ?, ?, CAST(? AS jsonb))")
                .params(id, caseId, type, actor, OpsJson.toJson(payload)).update();
    }

    List<CaseView.Event> events(UUID caseId) {
        return jdbc.sql("SELECT * FROM case_events WHERE case_id = ? ORDER BY created_at, id").param(caseId)
                .query((rs, n) -> new CaseView.Event((UUID) rs.getObject("id"), rs.getString("type"),
                        rs.getString("actor"), json(rs.getString("payload"), MAP), instant(rs, "created_at")))
                .list();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("status"), rs.getString("goal"),
                rs.getString("runbook"), rs.getInt("version"), rs.getInt("state_version"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "resolved_at"));
    }

    private static CaseView.Entry entry(ResultSet rs, int n) throws SQLException {
        return new CaseView.Entry((UUID) rs.getObject("id"), rs.getString("kind"), rs.getString("status"),
                rs.getString("text"), rs.getString("source_type"), rs.getString("source_ref"),
                json(rs.getString("evidence"), LIST), json(rs.getString("data"), MAP),
                (UUID) rs.getObject("supersedes"), (UUID) rs.getObject("superseded_by"), rs.getString("run_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static <T> T json(String value, TypeReference<T> type) {
        return OpsJson.mapper().readValue(value, type);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
