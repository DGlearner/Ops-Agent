-- Service catalog (docs/project-design.md §6.4, §9): what runs, and what depends on what. Alerts are correlated along
-- these dependencies (§8.4). Keyed by name until the identity module brings teams.
CREATE TABLE services (
    name        text        PRIMARY KEY,
    kind        text        NOT NULL DEFAULT 'service',
    description text,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT services_kind CHECK (kind IN ('service', 'database', 'cache', 'queue', 'external'))
);

-- service depends on depends_on: a failure of depends_on can show up as symptoms in service.
CREATE TABLE service_dependencies (
    service    text NOT NULL REFERENCES services (name) ON DELETE CASCADE,
    depends_on text NOT NULL REFERENCES services (name) ON DELETE CASCADE,
    PRIMARY KEY (service, depends_on),
    CONSTRAINT service_dependencies_not_self CHECK (service <> depends_on)
);
