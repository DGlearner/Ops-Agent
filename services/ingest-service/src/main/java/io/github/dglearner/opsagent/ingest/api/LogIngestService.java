package io.github.dglearner.opsagent.ingest.api;

import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.ingest.publish.PublishFailedException;
import io.github.dglearner.opsagent.ingest.publish.RawLogPublisher;
import io.github.dglearner.opsagent.ingest.ratelimit.IngestRateLimiter;
import io.github.dglearner.opsagent.ingest.ratelimit.RateLimitDecision;
import io.github.dglearner.opsagent.ingest.ratelimit.RateLimitExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

@Service
public class LogIngestService {

    private final IngestRateLimiter rateLimiter;
    private final RawLogPublisher publisher;
    private final Clock clock;
    private final MeterRegistry meters;

    public LogIngestService(IngestRateLimiter rateLimiter, RawLogPublisher publisher, Clock clock,
            MeterRegistry meters) {
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Accepts a batch for one source.
     *
     * <p>With an {@code idempotencyKey}, request and event ids are derived from it, so a client that retries after a
     * timeout or 503 produces records with the same {@code event_id} and downstream consumers can drop duplicates.
     * Without one, ids are random UUIDv7.
     */
    public IngestResponse ingest(String sourceId, LogBatchRequest request, String idempotencyKey) {
        int lines = request.logs().size();
        RateLimitDecision decision = rateLimiter.tryAcquire(sourceId, lines);
        if (!decision.allowed()) {
            count(request, "rate_limited", lines);
            throw new RateLimitExceededException(decision.retryAfter());
        }

        UUID requestId = idempotencyKey == null
                ? Ids.newId()
                : Ids.nameBased("ingest|" + sourceId + "|" + idempotencyKey);
        Instant receivedAt = clock.instant();
        List<RawLogRecord> records = IntStream.range(0, lines)
                .mapToObj(i -> new RawLogRecord(
                        idempotencyKey == null ? Ids.newId() : Ids.nameBased(requestId + "|" + i),
                        requestId, sourceId, request.sourceType(), request.host(), request.service(),
                        request.sourceName(), request.logs().get(i), receivedAt))
                .toList();

        try {
            publisher.publish(records);
        } catch (PublishFailedException e) {
            count(request, "failed", lines);
            throw e;
        }
        count(request, "accepted", lines);
        return new IngestResponse(lines, requestId);
    }

    private void count(LogBatchRequest request, String outcome, int lines) {
        meters.counter("ops.ingest.lines", "source_type", request.sourceType().name().toLowerCase(),
                "outcome", outcome).increment(lines);
    }
}
