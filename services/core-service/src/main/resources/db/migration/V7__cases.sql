-- Cases: handling one fault from diagnosis to sign-off, across turns, sessions and people (docs/agent-design.md §2,
-- §13.1). What was said or seen (sources) and what currently holds (hypotheses, facts, constraints, decisions) are
-- entries; the case's version moves on every write, state_version only on what an action is decided on.
CREATE TABLE cases (
    id            uuid        PRIMARY KEY,
    title         text        NOT NULL,
    status        text        NOT NULL,
    goal          text,
    runbook       text,
    version       integer     NOT NULL DEFAULT 1,
    state_version integer     NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    CONSTRAINT cases_status CHECK (status IN ('open', 'diagnosing', 'mitigating', 'verifying', 'monitoring',
                                              'resolved', 'handed_over'))
);
CREATE INDEX cases_status ON cases (status, updated_at);

CREATE TABLE case_incidents (
    case_id     uuid        NOT NULL REFERENCES cases (id),
    incident_id uuid        NOT NULL REFERENCES incidents (id),
    open        boolean     NOT NULL DEFAULT true,
    added_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (case_id, incident_id)
);
-- An incident is in at most one case that is still being handled.
CREATE UNIQUE INDEX case_incidents_one_open ON case_incidents (incident_id) WHERE open;

CREATE TABLE case_entries (
    id            uuid        PRIMARY KEY,
    case_id       uuid        NOT NULL REFERENCES cases (id),
    kind          text        NOT NULL,
    status        text        NOT NULL,
    text          text        NOT NULL,
    source_type   text        NOT NULL,
    source_ref    text,
    evidence      jsonb       NOT NULL DEFAULT '[]',
    data          jsonb       NOT NULL DEFAULT '{}',
    supersedes    uuid        REFERENCES case_entries (id),
    superseded_by uuid        REFERENCES case_entries (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT case_entries_kind CHECK (kind IN ('statement', 'observation', 'fact', 'hypothesis', 'constraint',
                                                 'decision', 'note')),
    CONSTRAINT case_entries_source CHECK (source_type IN ('user', 'observation', 'rule', 'analyst', 'model',
                                                          'receipt', 'verification', 'system'))
);
CREATE INDEX case_entries_case ON case_entries (case_id, created_at);

CREATE TABLE case_events (
    id         uuid        PRIMARY KEY,
    case_id    uuid        NOT NULL REFERENCES cases (id),
    type       text        NOT NULL,
    actor      text        NOT NULL,
    payload    jsonb       NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX case_events_case ON case_events (case_id, created_at);
