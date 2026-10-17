package io.github.dglearner.opsagent.core.alerting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A row of {@code alerts}. The jsonb columns ({@code group_values}, {@code sample_event_ids}, {@code samples}) are
 * kept as JSON text here and read or written with the contracts' mapper, so they have the same shape as in the
 * Kafka message.
 */
@TableName("alerts")
public class AlertRecord {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private String ruleId;
    private Integer ruleVersion;
    private String fingerprint;
    private String severity;
    private String groupKey;
    private String service;
    private String host;
    private String groupValues;
    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;
    private Long count;
    private Integer threshold;
    private OffsetDateTime firstEventAt;
    private OffsetDateTime lastEventAt;
    private String summary;
    private String sampleEventIds;
    private String samples;
    private UUID incidentId;
    private OffsetDateTime createdAt;
    private OffsetDateTime storedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(Integer ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getGroupValues() {
        return groupValues;
    }

    public void setGroupValues(String groupValues) {
        this.groupValues = groupValues;
    }

    public OffsetDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(OffsetDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public OffsetDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(OffsetDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public OffsetDateTime getFirstEventAt() {
        return firstEventAt;
    }

    public void setFirstEventAt(OffsetDateTime firstEventAt) {
        this.firstEventAt = firstEventAt;
    }

    public OffsetDateTime getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(OffsetDateTime lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSampleEventIds() {
        return sampleEventIds;
    }

    public void setSampleEventIds(String sampleEventIds) {
        this.sampleEventIds = sampleEventIds;
    }

    public String getSamples() {
        return samples;
    }

    public void setSamples(String samples) {
        this.samples = samples;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(UUID incidentId) {
        this.incidentId = incidentId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getStoredAt() {
        return storedAt;
    }

    public void setStoredAt(OffsetDateTime storedAt) {
        this.storedAt = storedAt;
    }
}
