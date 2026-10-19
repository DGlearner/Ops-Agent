package io.github.dglearner.shop.order.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A mutex in Redis with a lease, written out by hand (docs/shop-design.md §17.4):
 * <ul>
 *   <li>{@code SET key token NX PX lease}: one command, so taking the lock and its expiry cannot be separated (SETNX
 *       then EXPIRE could leave a lock that never expires if the holder dies in between);</li>
 *   <li>the token is the holder's own random value, and release deletes the key only if it still holds that token,
 *       in one Lua script: after a holder's lease ran out and someone else took the lock, a late release would
 *       otherwise delete the new holder's lock;</li>
 *   <li>the lease does not make it safe on its own: a holder that pauses longer than the lease (GC, a slow query)
 *       still believes it holds the lock while another holder works. Redisson's watchdog renews the lease while the
 *       holder is alive, which shrinks this but cannot rule it out. Where correctness matters the protected write
 *       must check a fencing token itself; the product cache does that with the product version.</li>
 * </ul>
 * Not reentrant and no queue of waiters: the product cache only needs "one rebuild at a time per key".
 */
@Component
public class RedisLock {

    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The token to release with, or empty if someone else holds the lock. */
    public Optional<String> tryAcquire(String key, Duration lease) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, lease)) ? Optional.of(token)
                : Optional.empty();
    }

    /** Releases the lock if this token still holds it; false if the lease had run out (and maybe someone else took it). */
    public boolean release(String key, String token) {
        return Long.valueOf(1).equals(redis.execute(RELEASE, List.of(key), token));
    }
}
