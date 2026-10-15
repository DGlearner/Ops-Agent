package io.github.dglearner.opsagent.ingest.ratelimit;

/** Limits how many log lines each source may send. */
public interface IngestRateLimiter {

    RateLimitDecision tryAcquire(String sourceId, int lines);
}
