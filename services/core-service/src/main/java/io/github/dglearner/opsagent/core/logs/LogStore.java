package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.OpsJson;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes log lines in batches. Plain JDBC rather than a MyBatis mapper: one batched statement per poll, which the
 * driver rewrites into multi-row inserts ({@code reWriteBatchedInserts}). A line already stored (a redelivery) is
 * skipped by the key.
 */
@Component
class LogStore {

    static final int MAX_MESSAGE = 4000;

    private static final String INSERT = """
            INSERT INTO log_events (received_at, event_id, ts, service, host, source_type, event_type, severity,
                                    trace_id, message, fields)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT DO NOTHING""";

    private final JdbcTemplate jdbc;

    LogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(List<NormalizedEvent> events) {
        jdbc.batchUpdate(INSERT, events, events.size(), (ps, e) -> {
            ps.setObject(1, LogConsumer.arrival(e).atOffset(ZoneOffset.UTC));
            ps.setObject(2, e.eventId());
            ps.setObject(3, e.timestamp().atOffset(ZoneOffset.UTC));
            ps.setString(4, e.service());
            ps.setString(5, e.host());
            ps.setString(6, e.sourceType().name().toLowerCase(Locale.ROOT));
            ps.setString(7, e.eventType());
            ps.setString(8, e.severity().name().toLowerCase(Locale.ROOT));
            Object trace = e.fields().get("trace.id");
            ps.setString(9, trace == null ? null : trace.toString());
            ps.setString(10, e.message().length() > MAX_MESSAGE ? e.message().substring(0, MAX_MESSAGE) : e.message());
            ps.setString(11, OpsJson.toJson(e.fields()));
        });
    }
}
