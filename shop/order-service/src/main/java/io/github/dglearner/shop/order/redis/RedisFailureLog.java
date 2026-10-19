package io.github.dglearner.shop.order.redis;

import io.github.dglearner.shop.common.LogEvents;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Logs failed Redis calls as {@code dependency_call_failed} on {@code shop-redis}, at most once a second with the
 * number of failures since the last line. A flash sale can send thousands of requests a second; one line each would
 * flood the log while Redis is down, and a line a second is still enough for detection rules counting per minute.
 * Shared by everything in order-service that uses Redis: flash sales and the product cache.
 */
@Component
public class RedisFailureLog {

    private static final Logger log = LoggerFactory.getLogger(RedisFailureLog.class);
    private static final long INTERVAL = Duration.ofSeconds(1).toNanos();

    private final LongAdder failures = new LongAdder();
    private final AtomicLong nextLineAt = new AtomicLong(System.nanoTime());

    public void failed(String operation, DataAccessException e) {
        failures.increment();
        long now = System.nanoTime();
        long next = nextLineAt.get();
        if (now - next >= 0 && nextLineAt.compareAndSet(next, now + INTERVAL)) {
            log.atWarn().addKeyValue(LogEvents.ACTION, "dependency_call_failed")
                    .addKeyValue("dependency", "shop-redis").addKeyValue("operation", operation)
                    .addKeyValue("failure", e.getClass().getSimpleName())
                    .addKeyValue("failures", failures.sumThenReset())
                    .log("Redis call failed: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
