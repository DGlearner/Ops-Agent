package io.github.dglearner.opsagent.ingest.ratelimit;

import java.time.Duration;

public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded; retry after " + retryAfter.toMillis() + " ms");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
