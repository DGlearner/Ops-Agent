-- Spring Modulith's event publication registry, schema v2 of spring-modulith-events-jdbc 2.1.1
-- (org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql). An event between modules is recorded
-- here in the transaction that raised it and marked complete once its listener succeeded; incomplete ones are
-- delivered again after a restart.
CREATE TABLE event_publication (
    id                     uuid        NOT NULL,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
