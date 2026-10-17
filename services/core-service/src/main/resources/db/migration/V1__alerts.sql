-- Alerts from stream-processor (topic alerts, docs/project-design.md §7.5, §9).
-- id is the message's alert_id, derived from fingerprint and window start: a redelivered alert has the same id and
-- is stored once.
CREATE TABLE alerts (
    id               uuid        PRIMARY KEY,
    rule_id          text        NOT NULL,
    rule_version     integer     NOT NULL,
    fingerprint      text        NOT NULL,
    severity         text        NOT NULL,
    group_key        text        NOT NULL,
    service          text,
    host             text,
    group_values     jsonb       NOT NULL DEFAULT '{}',
    window_start     timestamptz NOT NULL,
    window_end       timestamptz NOT NULL,
    count            bigint      NOT NULL,
    threshold        integer     NOT NULL,
    first_event_at   timestamptz,
    last_event_at    timestamptz,
    summary          text        NOT NULL,
    sample_event_ids jsonb       NOT NULL DEFAULT '[]',
    samples          jsonb       NOT NULL DEFAULT '[]',
    incident_id      uuid,
    created_at       timestamptz NOT NULL,
    stored_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT alerts_severity CHECK (severity IN ('info', 'low', 'medium', 'high', 'critical'))
);

-- Newest first, the default listing; the id breaks ties for the cursor.
CREATE INDEX alerts_created ON alerts (created_at DESC, id DESC);
CREATE INDEX alerts_service_created ON alerts (service, created_at DESC);
CREATE INDEX alerts_rule_created ON alerts (rule_id, created_at DESC);
