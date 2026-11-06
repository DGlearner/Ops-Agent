package io.github.dglearner.opsagent.core.remediation;

import io.github.dglearner.opsagent.contracts.OpsJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

@Component
class RemediationStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    RemediationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String actionId, UUID caseId, UUID incidentId, String op, Map<String, Object> params,
            String digest, String runId, String sessionId, String snapshotDigest) {
        jdbc.sql("""
                INSERT INTO remediations (id, action_id, case_id, incident_id, op, params, payload_digest, status,
                                          run_id, session_id, snapshot_digest)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'accepted', ?, ?, ?)""")
                .params(id, actionId, caseId, incidentId, op, OpsJson.toJson(params), digest, runId, sessionId,
                        snapshotDigest).update();
    }

    /** Only an accepted remediation is finished, once. */
    int finish(UUID id, String status, Map<String, Object> precheck, Map<String, Object> receipt) {
        return jdbc.sql("""
                UPDATE remediations SET status = ?, precheck = CAST(? AS jsonb), receipt = CAST(? AS jsonb),
                    finished_at = now()
                WHERE id = ? AND status = 'accepted'""")
                .params(status, OpsJson.toJson(precheck), OpsJson.toJson(receipt), id).update();
    }

    Optional<RemediationView> byAction(String actionId) {
        return jdbc.sql("SELECT * FROM remediations WHERE action_id = ?").param(actionId)
                .query(RemediationStore::remediation).optional();
    }

    Optional<RemediationView> find(UUID id) {
        return jdbc.sql("SELECT * FROM remediations WHERE id = ?").param(id).query(RemediationStore::remediation)
                .optional();
    }

    List<RemediationView> forCase(UUID caseId) {
        return jdbc.sql("SELECT * FROM remediations WHERE case_id = ? ORDER BY created_at").param(caseId)
                .query(RemediationStore::remediation).list();
    }

    void insertVerification(UUID id, UUID caseId, UUID remediationId, String runId, Map<String, Object> criteria,
            Instant from, Instant notBefore) {
        jdbc.sql("""
                INSERT INTO verifications (id, case_id, remediation_id, run_id, criteria, window_from, not_before)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)""")
                .params(id, caseId, remediationId, runId, OpsJson.toJson(criteria), utc(from), utc(notBefore))
                .update();
    }

    /** The case's verifications not done yet are no longer about its current state. */
    int staleOpen(UUID caseId) {
        return jdbc.sql("UPDATE verifications SET status = 'stale' WHERE case_id = ? AND status IN ('pending', 'leased')")
                .param(caseId).update();
    }

    /** Claims the next due verification, or one whose worker's lease ran out: a new token each time. */
    Optional<VerificationView> claim(String worker, Duration lease) {
        return jdbc.sql("""
                UPDATE verifications SET status = 'leased', lease_owner = :worker,
                    lease_until = now() + make_interval(secs => :lease), token = token + 1, attempts = attempts + 1
                WHERE id = (SELECT id FROM verifications
                            WHERE (status = 'pending' AND not_before <= now())
                               OR (status = 'leased' AND lease_until < now())
                            ORDER BY not_before LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING *""").param("worker", worker).param("lease", (double) lease.toSeconds())
                .query((rs, n) -> verification(rs, true)).optional();
    }

    /** 0 unless it is still leased with this token. */
    int complete(UUID id, int token, String outcome, Map<String, Object> result) {
        return jdbc.sql("""
                UPDATE verifications SET status = 'done', outcome = ?, result = CAST(? AS jsonb), completed_at = now()
                WHERE id = ? AND token = ? AND status = 'leased'""")
                .params(outcome, OpsJson.toJson(result), id, token).update();
    }

    /** A stale verification's late result is kept, for the record; it changes nothing. */
    int keepStaleResult(UUID id, int token, String outcome, Map<String, Object> result) {
        return jdbc.sql("""
                UPDATE verifications SET outcome = ?, result = CAST(? AS jsonb), completed_at = now()
                WHERE id = ? AND token = ? AND status = 'stale'""")
                .params(outcome, OpsJson.toJson(result), id, token).update();
    }

    Optional<VerificationView> verification(UUID id) {
        return jdbc.sql("SELECT * FROM verifications WHERE id = ?").param(id)
                .query((rs, n) -> verification(rs, false)).optional();
    }

    List<VerificationView> verifications(UUID caseId) {
        return jdbc.sql("SELECT * FROM verifications WHERE case_id = ? ORDER BY created_at").param(caseId)
                .query((rs, n) -> verification(rs, false)).list();
    }

    private static RemediationView remediation(ResultSet rs, int n) throws SQLException {
        return new RemediationView((UUID) rs.getObject("id"), rs.getString("action_id"),
                (UUID) rs.getObject("case_id"), (UUID) rs.getObject("incident_id"), rs.getString("op"),
                json(rs.getString("params")), rs.getString("payload_digest"), rs.getString("status"),
                json(rs.getString("precheck")), json(rs.getString("receipt")), rs.getString("run_id"),
                rs.getString("session_id"), rs.getString("snapshot_digest"), instant(rs, "created_at"),
                instant(rs, "finished_at"));
    }

    private static VerificationView verification(ResultSet rs, boolean withToken) throws SQLException {
        String result = rs.getString("result");
        return new VerificationView((UUID) rs.getObject("id"), (UUID) rs.getObject("case_id"),
                (UUID) rs.getObject("remediation_id"), rs.getString("run_id"), json(rs.getString("criteria")),
                instant(rs, "window_from"),
                instant(rs, "not_before"), rs.getString("status"), rs.getString("lease_owner"),
                instant(rs, "lease_until"), withToken ? rs.getInt("token") : null, rs.getInt("attempts"),
                rs.getString("outcome"), result == null ? null : json(result), instant(rs, "created_at"),
                instant(rs, "completed_at"));
    }

    private static Map<String, Object> json(String value) {
        return OpsJson.mapper().readValue(value, MAP);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
