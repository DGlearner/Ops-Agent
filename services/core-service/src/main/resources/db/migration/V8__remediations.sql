-- Actions that change the system being watched, run once per confirmed card, and the checks afterwards of whether
-- it recovered (docs/agent-design.md §5, §6, §13.2).
CREATE TABLE remediations (
    id             uuid        PRIMARY KEY,
    action_id      text        NOT NULL UNIQUE,
    case_id        uuid        NOT NULL REFERENCES cases (id),
    incident_id    uuid        REFERENCES incidents (id),
    op             text        NOT NULL,
    params         jsonb       NOT NULL,
    payload_digest text        NOT NULL,
    status         text        NOT NULL,
    precheck       jsonb       NOT NULL DEFAULT '{}',
    receipt        jsonb       NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    CONSTRAINT remediations_status CHECK (status IN ('accepted', 'executed', 'failed', 'blocked', 'unknown'))
);
CREATE INDEX remediations_case ON remediations (case_id, created_at);

CREATE TABLE verifications (
    id             uuid        PRIMARY KEY,
    case_id        uuid        NOT NULL REFERENCES cases (id),
    remediation_id uuid        NOT NULL REFERENCES remediations (id),
    criteria       jsonb       NOT NULL,
    window_from    timestamptz NOT NULL,
    not_before     timestamptz NOT NULL,
    status         text        NOT NULL DEFAULT 'pending',
    lease_owner    text,
    lease_until    timestamptz,
    -- Goes up on every claim; a result is taken only with the current one, so a worker whose lease ran out cannot
    -- overwrite the one that took over.
    token          integer     NOT NULL DEFAULT 0,
    attempts       integer     NOT NULL DEFAULT 0,
    outcome        text,
    result         jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    completed_at   timestamptz,
    CONSTRAINT verifications_status CHECK (status IN ('pending', 'leased', 'done', 'stale')),
    CONSTRAINT verifications_outcome CHECK (outcome IS NULL OR outcome IN ('recovered', 'not_recovered',
                                                                           'inconclusive'))
);
CREATE INDEX verifications_due ON verifications (status, not_before);
CREATE INDEX verifications_case ON verifications (case_id, created_at);
