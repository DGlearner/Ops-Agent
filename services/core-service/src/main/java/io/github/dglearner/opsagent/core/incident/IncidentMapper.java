package io.github.dglearner.opsagent.core.incident;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Lists go through MyBatis-Plus's query wrappers; writes with jsonb, locks or conditions are written out. */
@Mapper
interface IncidentMapper extends BaseMapper<IncidentRecord> {

    /** Correlation runs one alert at a time, across threads and instances: a transaction-scoped advisory lock. */
    @Select("SELECT pg_advisory_xact_lock(hashtext('incident-correlation'))")
    Object lockCorrelation();

    @Insert("""
            INSERT INTO incidents (id, title, status, severity, services, subjects, alert_count, opened_at,
                                   last_alert_at)
            VALUES (#{id}, #{title}, #{status}, #{severity}, CAST(#{services} AS jsonb), CAST(#{subjects} AS jsonb),
                    #{alertCount}, #{openedAt}, #{lastAlertAt})""")
    void insertIncident(IncidentRecord incident);

    @Update("""
            UPDATE incidents
            SET severity = #{severity}, services = CAST(#{services} AS jsonb), subjects = CAST(#{subjects} AS jsonb),
                alert_count = alert_count + 1, opened_at = LEAST(opened_at, #{at}),
                last_alert_at = GREATEST(last_alert_at, #{at}), version = version + 1, updated_at = now()
            WHERE id = #{id}""")
    void attach(@Param("id") UUID id, @Param("severity") String severity, @Param("services") String services,
            @Param("subjects") String subjects, @Param("at") OffsetDateTime at);

    /** 0 if the incident changed since {@code version} was read. */
    @Update("""
            UPDATE incidents
            SET status = #{to},
                acknowledged_at = CASE WHEN #{to} = 'acknowledged' AND acknowledged_at IS NULL THEN now()
                                       ELSE acknowledged_at END,
                resolved_at = CASE WHEN #{to} = 'resolved' THEN now() WHEN #{to} = 'open' THEN NULL
                                   ELSE resolved_at END,
                closed_at = CASE WHEN #{to} = 'closed' THEN now() ELSE closed_at END,
                version = version + 1, updated_at = now()
            WHERE id = #{id} AND version = #{version}""")
    int transition(@Param("id") UUID id, @Param("to") String to, @Param("version") int version);

    @Insert("INSERT INTO incident_alerts (alert_id, incident_id) VALUES (#{alertId}, #{incidentId})")
    void link(@Param("alertId") UUID alertId, @Param("incidentId") UUID incidentId);

    @Select("SELECT count(*) > 0 FROM incident_alerts WHERE alert_id = #{alertId}")
    boolean linked(@Param("alertId") UUID alertId);

    @Select("SELECT alert_id FROM incident_alerts WHERE incident_id = #{incidentId}")
    List<UUID> alertIds(@Param("incidentId") UUID incidentId);

    @Insert("""
            INSERT INTO incident_events (id, incident_id, type, actor_type, from_status, to_status, payload)
            VALUES (#{id}, #{incidentId}, #{type}, #{actorType}, #{fromStatus}, #{toStatus},
                    CAST(#{payload} AS jsonb))""")
    void insertEvent(@Param("id") UUID id, @Param("incidentId") UUID incidentId, @Param("type") String type,
            @Param("actorType") String actorType, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("payload") String payload);

    @Select("""
            SELECT id, type, actor_type, from_status, to_status, payload::text AS payload, created_at
            FROM incident_events WHERE incident_id = #{incidentId} ORDER BY created_at, id""")
    List<Map<String, Object>> events(@Param("incidentId") UUID incidentId);

    /** Active incidents whose latest alert was attached between {@code from} and {@code to}, by arrival here. */
    @Select("""
            SELECT e.incident_id, max(e.created_at) AS last_attached_at
            FROM incident_events e JOIN incidents i ON i.id = e.incident_id
            WHERE i.status IN ('open', 'acknowledged', 'investigating', 'mitigated') AND i.updated_at >= #{from}
              AND e.type IN ('OPENED', 'ALERT_ATTACHED')
            GROUP BY e.incident_id
            HAVING max(e.created_at) BETWEEN #{from} AND #{to}""")
    List<Map<String, Object>> settled(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
