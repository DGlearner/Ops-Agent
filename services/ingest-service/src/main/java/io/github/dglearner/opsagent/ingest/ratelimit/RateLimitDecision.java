package io.github.dglearner.opsagent.ingest.ratelimit;

import java.time.Duration;

/** @param retryAfter when the request would fit into the bucket; zero when allowed */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    public static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);
}
