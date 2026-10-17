-- Incidents: alerts correlated along service dependencies (docs/project-design.md §8.4, §8.5, §9).
CREATE TABLE incidents (
    id              uuid        PRIMARY KEY,
    title           text        NOT NULL,
    status          text        NOT NULL,
    severity        text        NOT NULL,
    -- Where the symptoms were seen, and what the alerts point at (the callee of a failing call, else the service).
    services        jsonb       NOT NULL DEFAULT '[]',
    subjects        jsonb       NOT NULL DEFAULT '[]',
    alert_count     integer     NOT NULL DEFAULT 0,
    opened_at       timestamptz NOT NULL,
    last_alert_at   timestamptz NOT NULL,
    acknowledged_at timestamptz,
    resolved_at     timestamptz,
    closed_at       timestamptz,
    version         integer     NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT incidents_status CHECK (status IN ('open', 'acknowledged', 'investigating', 'mitigated', 'resolved',
                                                  'closed')),
    CONSTRAINT incidents_severity CHECK (severity IN ('info', 'low', 'medium', 'high', 'critical'))
);
CREATE INDEX incidents_active ON incidents (status, last_alert_at);

-- An alert belongs to at most one incident.
CREATE TABLE incident_alerts (
    alert_id    uuid        PRIMARY KEY,
    incident_id uuid        NOT NULL REFERENCES incidents (id),
    attached_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incident_alerts_incident ON incident_alerts (incident_id);

CREATE TABLE incident_events (
    id          uuid        PRIMARY KEY,
    incident_id uuid        NOT NULL REFERENCES incidents (id),
    type        text        NOT NULL,
    actor_type  text        NOT NULL,
    from_status text,
    to_status   text,
    payload     jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incident_events_incident ON incident_events (incident_id, created_at);

-- The link from alerts to incidents is the incident module's (incident_alerts): the alerting module does not know
-- about incidents.
ALTER TABLE alerts DROP COLUMN incident_id;
