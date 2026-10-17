package io.github.dglearner.opsagent.core.incident;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.alerting.AlertView;
import io.github.dglearner.opsagent.core.alerting.Alerts;
import io.github.dglearner.opsagent.core.catalog.Catalog;
import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/** The incident module's API: incidents, their alerts and history, and status changes. */
@Service
public class Incidents {

    /** Which incidents to list; null means any. {@code from} inclusive, {@code to} exclusive, on the opening time. */
    public record Filter(IncidentStatus status, Severity severity, String service, Instant from, Instant to) {
    }

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    private final IncidentMapper mapper;
    private final Alerts alerts;
    private final Catalog catalog;

    Incidents(IncidentMapper mapper, Alerts alerts, Catalog catalog) {
        this.mapper = mapper;
        this.alerts = alerts;
        this.catalog = catalog;
    }

    /** Active incidents with an alert within {@code gap} of {@code at}: those a new alert at {@code at} may join. */
    static LambdaQueryWrapper<IncidentRecord> active(Instant at, Duration gap) {
        return Wrappers.<IncidentRecord>lambdaQuery()
                .in(IncidentRecord::getStatus, IncidentStatus.ACTIVE.stream().map(IncidentStatus::wire).toList())
                .ge(IncidentRecord::getLastAlertAt, at.minus(gap).atOffset(ZoneOffset.UTC))
                .le(IncidentRecord::getOpenedAt, at.plus(gap).atOffset(ZoneOffset.UTC));
    }

    public Optional<IncidentView> find(UUID incidentId) {
        return Optional.ofNullable(mapper.selectById(incidentId)).map(this::view);
    }

    /** Newest first. Incident ids are UUIDv7, ordered by time, so the cursor is the last id of the page. */
    public IncidentPage list(Filter filter, int limit, String cursor) {
        LambdaQueryWrapper<IncidentRecord> query = Wrappers.<IncidentRecord>lambdaQuery()
                .eq(filter.status() != null, IncidentRecord::getStatus,
                        filter.status() == null ? null : filter.status().wire())
                .eq(filter.severity() != null, IncidentRecord::getSeverity,
                        filter.severity() == null ? null : filter.severity().name().toLowerCase(Locale.ROOT))
                .apply(filter.service() != null, "services @> CAST({0} AS jsonb)",
                        OpsJson.toJson(List.of(String.valueOf(filter.service()))))
                .ge(filter.from() != null, IncidentRecord::getOpenedAt, utc(filter.from()))
                .lt(filter.to() != null, IncidentRecord::getOpenedAt, utc(filter.to()));
        if (cursor != null) {
            query.lt(IncidentRecord::getId, cursorId(cursor));
        }
        query.orderByDesc(IncidentRecord::getId).last("LIMIT " + (limit + 1));
        List<IncidentRecord> rows = mapper.selectList(query);
        boolean more = rows.size() > limit;
        List<IncidentRecord> page = more ? rows.subList(0, limit) : rows;
        return new IncidentPage(page.stream().map(this::view).toList(),
                more ? page.getLast().getId().toString() : null);
    }

    public List<AlertView> alerts(UUID incidentId) {
        require(incidentId);
        return alerts.findAll(mapper.alertIds(incidentId));
    }

    public List<TimelineEntry> timeline(UUID incidentId) {
        require(incidentId);
        List<TimelineEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : mapper.events(incidentId)) {
            entries.add(new TimelineEntry((UUID) row.get("id"), (String) row.get("type"),
                    (String) row.get("actor_type"), (String) row.get("from_status"), (String) row.get("to_status"),
                    OpsJson.mapper().readValue((String) row.get("payload"), PAYLOAD), instant(row.get("created_at"))));
        }
        return entries;
    }

    /** Moves the incident to {@code to}; 409 if the lifecycle does not allow it or someone changed it meanwhile. */
    @Transactional
    public IncidentView transition(UUID incidentId, IncidentStatus to, String note) {
        IncidentRecord incident = require(incidentId);
        IncidentStatus from = IncidentStatus.of(incident.getStatus());
        if (!from.canMoveTo(to)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION",
                    "An incident cannot go from " + from.wire() + " to " + to.wire());
        }
        if (mapper.transition(incidentId, to.wire(), incident.getVersion()) != 1) {
            throw new ApiProblem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                    "The incident changed meanwhile; read it again and retry");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (note != null) {
            payload.put("note", note);
        }
        mapper.insertEvent(Ids.newId(), incidentId, "STATUS_CHANGED", "USER", from.wire(), to.wire(),
                OpsJson.toJson(payload));
        return view(mapper.selectById(incidentId));
    }

    private IncidentRecord require(UUID incidentId) {
        IncidentRecord incident = mapper.selectById(incidentId);
        if (incident == null) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "No incident " + incidentId);
        }
        return incident;
    }

    private IncidentView view(IncidentRecord row) {
        List<String> subjects = IncidentCorrelator.strings(row.getSubjects());
        return new IncidentView(row.getId(), row.getTitle(), row.getStatus(),
                Severity.valueOf(row.getSeverity().toUpperCase(Locale.ROOT)),
                IncidentCorrelator.strings(row.getServices()), subjects,
                Correlation.sharedDependencies(subjects, catalog.topology()), row.getAlertCount(),
                instant(row.getOpenedAt()), instant(row.getLastAlertAt()), instant(row.getAcknowledgedAt()),
                instant(row.getResolvedAt()), instant(row.getClosedAt()), row.getVersion());
    }

    private static UUID cursorId(String cursor) {
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Not a cursor from this API: " + cursor);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object time) {
        return switch (time) {
            case null -> null;
            case OffsetDateTime t -> t.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            case Instant t -> t;
            default -> throw new IllegalStateException("Not a time: " + time.getClass());
        };
    }
}
