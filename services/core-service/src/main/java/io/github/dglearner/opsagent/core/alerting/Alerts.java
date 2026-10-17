package io.github.dglearner.opsagent.core.alerting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/** The alerting module's API: stores alerts from stream-processor and answers queries about them. */
@Service
public class Alerts {

    private static final TypeReference<Map<String, String>> GROUP_VALUES = new TypeReference<>() {
    };
    private static final TypeReference<List<UUID>> EVENT_IDS = new TypeReference<>() {
    };
    private static final TypeReference<List<Alert.Sample>> SAMPLES = new TypeReference<>() {
    };

    private final AlertMapper mapper;

    Alerts(AlertMapper mapper) {
        this.mapper = mapper;
    }

    /** True if the alert is new; false if it was stored before (a redelivery or a replay). */
    public boolean store(Alert alert) {
        return mapper.insertIfAbsent(record(alert)) == 1;
    }

    public Optional<AlertView> find(UUID alertId) {
        return Optional.ofNullable(mapper.selectById(alertId)).map(Alerts::view);
    }

    /**
     * Newest first. Keyset pagination on (created_at, id): alert ids are derived from their content, not time-ordered,
     * so the cursor carries the creation time of the last alert of the page as well as its id.
     */
    public AlertPage list(AlertFilter filter, int limit, String cursor) {
        LambdaQueryWrapper<AlertRecord> query = Wrappers.<AlertRecord>lambdaQuery()
                .eq(filter.severity() != null, AlertRecord::getSeverity, name(filter.severity()))
                .eq(filter.service() != null, AlertRecord::getService, filter.service())
                .eq(filter.ruleId() != null, AlertRecord::getRuleId, filter.ruleId())
                .ge(filter.from() != null, AlertRecord::getCreatedAt, utc(filter.from()))
                .lt(filter.to() != null, AlertRecord::getCreatedAt, utc(filter.to()));
        if (cursor != null) {
            Cursor after = Cursor.decode(cursor);
            query.and(q -> q.lt(AlertRecord::getCreatedAt, after.createdAt())
                    .or(same -> same.eq(AlertRecord::getCreatedAt, after.createdAt())
                            .lt(AlertRecord::getId, after.id())));
        }
        query.orderByDesc(AlertRecord::getCreatedAt, AlertRecord::getId).last("LIMIT " + (limit + 1));

        List<AlertRecord> rows = mapper.selectList(query);
        boolean more = rows.size() > limit;
        List<AlertRecord> page = more ? rows.subList(0, limit) : rows;
        String next = more ? Cursor.of(page.getLast()).encode() : null;
        return new AlertPage(page.stream().map(Alerts::view).toList(), next);
    }

    private static AlertRecord record(Alert alert) {
        AlertRecord row = new AlertRecord();
        row.setId(alert.alertId());
        row.setRuleId(alert.ruleId());
        row.setRuleVersion(alert.ruleVersion());
        row.setFingerprint(alert.fingerprint());
        row.setSeverity(name(alert.severity()));
        row.setGroupKey(alert.groupKey());
        row.setService(alert.service());
        row.setHost(alert.host());
        row.setGroupValues(OpsJson.toJson(alert.groupValues()));
        row.setWindowStart(utc(alert.windowStart()));
        row.setWindowEnd(utc(alert.windowEnd()));
        row.setCount(alert.count());
        row.setThreshold(alert.threshold());
        row.setFirstEventAt(utc(alert.firstEventAt()));
        row.setLastEventAt(utc(alert.lastEventAt()));
        row.setSummary(alert.summary());
        row.setSampleEventIds(OpsJson.toJson(alert.sampleEventIds()));
        row.setSamples(OpsJson.toJson(alert.samples()));
        row.setCreatedAt(utc(alert.createdAt()));
        return row;
    }

    private static AlertView view(AlertRecord row) {
        return new AlertView(row.getId(), row.getRuleId(), row.getRuleVersion(), row.getFingerprint(),
                Severity.valueOf(row.getSeverity().toUpperCase(Locale.ROOT)), row.getGroupKey(), row.getService(),
                row.getHost(), OpsJson.mapper().readValue(row.getGroupValues(), GROUP_VALUES),
                instant(row.getWindowStart()), instant(row.getWindowEnd()), row.getCount(), row.getThreshold(),
                instant(row.getFirstEventAt()), instant(row.getLastEventAt()), row.getSummary(),
                OpsJson.mapper().readValue(row.getSampleEventIds(), EVENT_IDS),
                OpsJson.mapper().readValue(row.getSamples(), SAMPLES), row.getIncidentId(),
                instant(row.getCreatedAt()), instant(row.getStoredAt()));
    }

    private static String name(Severity severity) {
        return severity == null ? null : severity.name().toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    /** Position after the last alert of a page; opaque to clients. */
    private record Cursor(OffsetDateTime createdAt, UUID id) {

        static Cursor of(AlertRecord row) {
            return new Cursor(row.getCreatedAt(), row.getId());
        }

        String encode() {
            String text = createdAt.toInstant() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(Instant.parse(parts[0]).atOffset(ZoneOffset.UTC), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Not a cursor from this API: " + cursor);
            }
        }
    }
}
