package io.github.dglearner.opsagent.core.alerting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** Reads go through MyBatis-Plus's query wrappers; the insert is written out for its conflict handling. */
@Mapper
interface AlertMapper extends BaseMapper<AlertRecord> {

    /** Stores the alert unless one with its id is stored already; 1 if it was new, 0 if it was a redelivery. */
    @Insert("""
            INSERT INTO alerts (id, rule_id, rule_version, fingerprint, severity, group_key, service, host,
                                group_values, window_start, window_end, count, threshold, first_event_at,
                                last_event_at, summary, sample_event_ids, samples, created_at)
            VALUES (#{id}, #{ruleId}, #{ruleVersion}, #{fingerprint}, #{severity}, #{groupKey}, #{service}, #{host},
                    CAST(#{groupValues} AS jsonb), #{windowStart}, #{windowEnd}, #{count}, #{threshold},
                    #{firstEventAt}, #{lastEventAt}, #{summary}, CAST(#{sampleEventIds} AS jsonb),
                    CAST(#{samples} AS jsonb), #{createdAt})
            ON CONFLICT (id) DO NOTHING""")
    int insertIfAbsent(AlertRecord alert);
}
