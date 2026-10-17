package io.github.dglearner.opsagent.core.agentops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
interface AnalysisMapper extends BaseMapper<AnalysisRecord> {

    /** 0 if the incident has a pending analysis already. */
    @Insert("""
            INSERT INTO incident_analyses (id, incident_id, status, requested_by, requested_at, deadline)
            VALUES (#{id}, #{incidentId}, 'pending', #{requestedBy}, #{requestedAt}, #{deadline})
            ON CONFLICT DO NOTHING""")
    int insertPending(@Param("id") UUID id, @Param("incidentId") UUID incidentId,
            @Param("requestedBy") String requestedBy, @Param("requestedAt") OffsetDateTime requestedAt,
            @Param("deadline") OffsetDateTime deadline);

    /** Stores the agent's answer; also for a late answer to an analysis already marked timed out. 0 if unknown. */
    @Update("""
            UPDATE incident_analyses
            SET status = #{status}, completed_at = #{completedAt}, summary = #{summary}, uncertain = #{uncertain},
                hypotheses = CAST(#{hypotheses} AS jsonb), next_steps = CAST(#{nextSteps} AS jsonb),
                rejected_evidence = CAST(#{rejectedEvidence} AS jsonb), model = #{model}, tool_calls = #{toolCalls},
                duration_ms = #{durationMs}, error = #{error}
            WHERE id = #{id} AND incident_id = #{incidentId} AND status IN ('pending', 'timed_out')""")
    int complete(AnalysisRecord result);

    @Update("UPDATE incident_analyses SET status = 'timed_out' WHERE status = 'pending' AND deadline < now()")
    int expirePending();
}
