package io.github.dglearner.opsagent.core.agentops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A row of {@code incident_analyses}; the jsonb columns are kept as JSON text. */
@TableName("incident_analyses")
public class AnalysisRecord {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID incidentId;
    private String status;
    private String requestedBy;
    private OffsetDateTime requestedAt;
    private OffsetDateTime deadline;
    private OffsetDateTime completedAt;
    private String summary;
    private Boolean uncertain;
    private String hypotheses;
    private String nextSteps;
    private String rejectedEvidence;
    private String model;
    private Integer toolCalls;
    private Long durationMs;
    private String error;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(UUID incidentId) {
        this.incidentId = incidentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(OffsetDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public OffsetDateTime getDeadline() {
        return deadline;
    }

    public void setDeadline(OffsetDateTime deadline) {
        this.deadline = deadline;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Boolean getUncertain() {
        return uncertain;
    }

    public void setUncertain(Boolean uncertain) {
        this.uncertain = uncertain;
    }

    public String getHypotheses() {
        return hypotheses;
    }

    public void setHypotheses(String hypotheses) {
        this.hypotheses = hypotheses;
    }

    public String getNextSteps() {
        return nextSteps;
    }

    public void setNextSteps(String nextSteps) {
        this.nextSteps = nextSteps;
    }

    public String getRejectedEvidence() {
        return rejectedEvidence;
    }

    public void setRejectedEvidence(String rejectedEvidence) {
        this.rejectedEvidence = rejectedEvidence;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(Integer toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
