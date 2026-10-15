package io.github.dglearner.opsagent.ingest.ratelimit;

import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket per source in Redis (key {@code rl:ingest:<source>}), evaluated by a Lua script so the read, refill
 * and take happen atomically even with several ingest instances.
 *
 * <p>Fails open: if Redis is unavailable the request is allowed and {@code ops.ingest.ratelimit.errors} is
 * incremented. Losing logs during a Redis outage is worse than briefly not limiting.
 */
@Component
public class RedisTokenBucketRateLimiter implements IngestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final IngestProperties.RateLimit settings;
    private final Counter errors;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, IngestProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.settings = properties.rateLimit();
        this.errors = Counter.builder("ops.ingest.ratelimit.errors")
                .description("Rate limit checks that failed and were allowed through")
                .register(meters);
    }

    @Override
    public RateLimitDecision tryAcquire(String sourceId, int lines) {
        if (!settings.enabled()) {
            return RateLimitDecision.ALLOWED;
        }
        try {
            List<?> result = redis.execute(SCRIPT, List.of("rl:ingest:" + sourceId),
                    Long.toString(settings.capacity()), Long.toString(settings.refillPerSecond()),
                    Integer.toString(lines));
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            return allowed ? RateLimitDecision.ALLOWED
                    : new RateLimitDecision(false, Duration.ofMillis(((Number) result.get(1)).longValue()));
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("Rate limit check failed for source {}; allowing request: {}", sourceId, e.getMessage());
            return RateLimitDecision.ALLOWED;
        }
    }
}
