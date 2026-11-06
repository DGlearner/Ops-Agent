-- Parsed, redacted log lines from stream-processor (topic logs.normalized), for the agents and the console to search
-- (docs/project-design.md §8.7). Kept for a few days: partitioned by the day they arrived, and a day past retention
-- goes as a whole partition (logs.LogPartitions creates and drops them). By arrival rather than by the line's own
-- time, so that a replay of old logs lands in today's partition instead of one already dropped.
-- Primary key (received_at, event_id): a partitioned table's keys include the partition key. A redelivered message
-- has both the same and is stored once.

-- Keyword search ("JDBC Connection") matches anywhere in the message, which a trigram index serves.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA core;

CREATE TABLE log_events (
    received_at timestamptz NOT NULL,
    event_id    uuid        NOT NULL,
    ts          timestamptz NOT NULL,
    service     text,
    host        text        NOT NULL,
    source_type text        NOT NULL,
    event_type  text        NOT NULL,
    severity    text        NOT NULL,
    trace_id    text,
    message     text        NOT NULL,
    fields      jsonb       NOT NULL DEFAULT '{}',
    PRIMARY KEY (received_at, event_id),
    CONSTRAINT log_events_severity CHECK (severity IN ('info', 'low', 'medium', 'high', 'critical'))
) PARTITION BY RANGE (received_at);

-- Searches name a service and a time range, newest first.
CREATE INDEX log_events_service_ts ON log_events (service, ts DESC);
CREATE INDEX log_events_ts ON log_events (ts DESC);
CREATE INDEX log_events_trace ON log_events (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX log_events_message ON log_events USING gin (message gin_trgm_ops);

-- The first days' partitions, so that logs can be stored before LogPartitions first runs. Days are UTC days.
DO $$
DECLARE
    day date;
BEGIN
    FOR day IN SELECT generate_series((now() AT TIME ZONE 'UTC')::date - 1, (now() AT TIME ZONE 'UTC')::date + 2,
                                      interval '1 day')::date
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF log_events FOR VALUES FROM (%L) TO (%L)',
                       'log_events_' || to_char(day, 'YYYYMMDD'), day::text || ' 00:00:00+00',
                       (day + 1)::text || ' 00:00:00+00');
    END LOOP;
END
$$;
