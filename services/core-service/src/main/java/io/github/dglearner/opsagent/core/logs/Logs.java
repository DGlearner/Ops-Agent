package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * The logs module's API (docs/project-design.md §8.7): searches the stored lines and counts them. Every query names a
 * time range on the lines' own time; the partitions (by arrival) are pruned to those that can hold such lines, since
 * a line arrives after it was written.
 */
@Service
public class Logs {

    public enum CountBy {
        EVENT_TYPE("event_type"), SEVERITY("severity"), SERVICE("service"), HOST("host");

        final String column;

        CountBy(String column) {
            this.column = column;
        }
    }

    /** How long after its own time a line may arrive, at most, for the partition pruning; later ones are missed. */
    static final Duration MAX_DELAY = Duration.ofDays(1);
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() {
    };
    private static final String RANK = """
            CASE severity WHEN 'critical' THEN 4 WHEN 'high' THEN 3 WHEN 'medium' THEN 2 WHEN 'low' THEN 1 ELSE 0 END""";

    private final JdbcClient jdbc;

    Logs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first; keyset pagination on (ts, event_id). */
    public LogPage search(LogFilter filter, int limit, String cursor) {
        Query query = where(filter);
        if (cursor != null) {
            Cursor after = Cursor.decode(cursor);
            query.and("(ts < :after_ts OR (ts = :after_ts AND event_id < :after_id))")
                    .param("after_ts", utc(after.ts())).param("after_id", after.id());
        }
        List<LogEventView> rows = jdbc.sql("""
                SELECT event_id, ts, received_at, service, host, source_type, event_type, severity, trace_id, message,
                       fields::text AS fields
                FROM log_events WHERE %s ORDER BY ts DESC, event_id DESC LIMIT %d""".formatted(query.sql(), limit + 1))
                .params(query.params()).query((rs, n) -> view(rs)).list();
        boolean more = rows.size() > limit;
        List<LogEventView> page = more ? rows.subList(0, limit) : rows;
        return new LogPage(page, more ? new Cursor(page.getLast().timestamp(), page.getLast().eventId()).encode()
                : null);
    }

    /**
     * The values of one column in the range, the most frequent first. The example is the line at each group's last
     * time, looked up through the time index for the groups returned only: aggregating the messages themselves
     * (array_agg ordered by time) sorted every line of the range: counting 300 000 lines by service took 0.72 s that
     * way and takes 0.22 s this way (docs/project-design.md §8.7).
     */
    public List<LogCount> count(LogFilter filter, CountBy by, int limit) {
        Query query = where(filter);
        return jdbc.sql("""
                WITH groups AS (
                    SELECT %1$s AS key, count(*) AS count, max(%2$s) AS rank, min(ts) AS first_at, max(ts) AS last_at
                    FROM log_events WHERE %3$s GROUP BY %1$s ORDER BY count(*) DESC, %1$s LIMIT %4$d)
                SELECT groups.*, (SELECT message FROM log_events WHERE %3$s AND ts = groups.last_at
                                  AND %1$s IS NOT DISTINCT FROM groups.key LIMIT 1) AS example
                FROM groups ORDER BY count DESC, key"""
                .formatted(by.column, RANK, query.sql(), limit))
                .params(query.params())
                .query((rs, n) -> new LogCount(rs.getString("key"), rs.getLong("count"),
                        Severity.values()[rs.getInt("rank")], instant(rs, "first_at"), instant(rs, "last_at"),
                        rs.getString("example")))
                .list();
    }

    /** Which of these lines are stored with their own time in [from, to). */
    public Set<UUID> existing(Collection<UUID> eventIds, Instant from, Instant to) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        Query query = where(new LogFilter(from, to, null, null, null, null, null));
        query.and("event_id IN (:ids)").param("ids", List.copyOf(eventIds));
        return new HashSet<>(jdbc.sql("SELECT event_id FROM log_events WHERE " + query.sql()).params(query.params())
                .query(UUID.class).list());
    }

    private static Query where(LogFilter f) {
        Query q = new Query();
        q.and("ts >= :from AND ts < :to AND received_at >= :received_from")
                .param("from", utc(f.from())).param("to", utc(f.to()))
                .param("received_from", utc(f.from().minus(MAX_DELAY)));
        if (f.service() != null) {
            q.and("service = :service").param("service", f.service());
        }
        if (f.minSeverity() != null) {
            q.and("severity IN (:severities)").param("severities", Arrays.stream(Severity.values())
                    .filter(s -> s.isAtLeast(f.minSeverity())).map(s -> s.name().toLowerCase(Locale.ROOT)).toList());
        }
        if (f.eventType() != null) {
            q.and("event_type = :event_type").param("event_type", f.eventType());
        }
        if (f.traceId() != null) {
            q.and("trace_id = :trace_id").param("trace_id", f.traceId());
        }
        if (f.text() != null) {
            // The trigram index serves ILIKE '%…%'; the user's % and _ are literal.
            q.and("message ILIKE :text ESCAPE '\\'").param("text",
                    "%" + f.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return q;
    }

    private static LogEventView view(ResultSet rs) throws SQLException {
        return new LogEventView(rs.getObject("event_id", UUID.class), instant(rs, "ts"), instant(rs, "received_at"),
                rs.getString("service"), rs.getString("host"), rs.getString("source_type"), rs.getString("event_type"),
                Severity.valueOf(rs.getString("severity").toUpperCase(Locale.ROOT)), rs.getString("trace_id"),
                rs.getString("message"), OpsJson.mapper().readValue(rs.getString("fields"), FIELDS));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** A WHERE clause and its named parameters, built up condition by condition. */
    private static final class Query {

        private final List<String> conditions = new ArrayList<>();
        private final Map<String, Object> params = new LinkedHashMap<>();

        Query and(String condition) {
            conditions.add(condition);
            return this;
        }

        Query param(String name, Object value) {
            params.put(name, value);
            return this;
        }

        String sql() {
            return String.join(" AND ", conditions);
        }

        Map<String, Object> params() {
            return params;
        }
    }

    /** Position after the last line of a page; opaque to clients. */
    private record Cursor(Instant ts, UUID id) {

        String encode() {
            String text = ts + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Not a cursor from this API: " + cursor);
            }
        }
    }
}
