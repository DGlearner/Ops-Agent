package io.github.dglearner.opsagent.ingest.api;

import java.util.UUID;

/** @param requestId shared by all records of the request; stable across retries with the same Idempotency-Key */
public record IngestResponse(int accepted, UUID requestId) {
}
