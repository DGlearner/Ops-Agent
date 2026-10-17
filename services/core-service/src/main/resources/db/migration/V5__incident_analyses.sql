-- The agent's analyses of incidents (docs/project-design.md §7.6, §14.3 P4). id is the AgentTask's task_id.
CREATE TABLE incident_analyses (
    id                uuid        PRIMARY KEY,
    incident_id       uuid        NOT NULL REFERENCES incidents (id),
    status            text        NOT NULL,
    requested_by      text        NOT NULL,
    requested_at      timestamptz NOT NULL,
    deadline          timestamptz NOT NULL,
    completed_at      timestamptz,
    summary           text,
    uncertain         boolean,
    hypotheses        jsonb       NOT NULL DEFAULT '[]',
    next_steps        jsonb       NOT NULL DEFAULT '[]',
    -- Evidence the agent cited that the incident does not have: kept apart, never shown as support.
    rejected_evidence jsonb       NOT NULL DEFAULT '[]',
    model             text,
    tool_calls        integer,
    duration_ms       bigint,
    error             text,
    CONSTRAINT incident_analyses_status CHECK (status IN ('pending', 'completed', 'failed', 'timed_out'))
);
CREATE INDEX incident_analyses_incident ON incident_analyses (incident_id, requested_at);
-- At most one analysis of an incident waits for the agent at a time: asking again while one does returns that one.
CREATE UNIQUE INDEX incident_analyses_one_pending ON incident_analyses (incident_id) WHERE status = 'pending';
