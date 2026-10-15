package io.github.dglearner.opsagent.ingest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisTokenBucketRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private LettuceConnectionFactory connections;

    @AfterEach
    void close() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void allowsBurstUpToCapacityThenAsksToWait() {
        var limiter = limiter(REDIS.getHost(), REDIS.getMappedPort(6379), 10, 10);

        assertThat(limiter.tryAcquire("burst", 6).allowed()).isTrue();
        assertThat(limiter.tryAcquire("burst", 4).allowed()).isTrue();
        RateLimitDecision denied = limiter.tryAcquire("burst", 5);

        assertThat(denied.allowed()).isFalse();
        // 5 tokens at 10 per second
        assertThat(denied.retryAfter()).isBetween(Duration.ofMillis(300), Duration.ofMillis(500));
    }

    @Test
    void keepsSourcesSeparate() {
        var limiter = limiter(REDIS.getHost(), REDIS.getMappedPort(6379), 10, 10);

        assertThat(limiter.tryAcquire("source-a", 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire("source-a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("source-b", 10).allowed()).isTrue();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        var limiter = limiter(REDIS.getHost(), REDIS.getMappedPort(6379), 10, 20);
        assertThat(limiter.tryAcquire("refill", 10).allowed()).isTrue();

        Thread.sleep(300); // about 6 tokens at 20 per second

        assertThat(limiter.tryAcquire("refill", 4).allowed()).isTrue();
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        var limiter = limiter("127.0.0.1", 1, 10, 10);

        assertThat(limiter.tryAcquire("any", 1000).allowed()).isTrue();
        assertThat(meters.counter("ops.ingest.ratelimit.errors").count()).isEqualTo(1);
    }

    private RedisTokenBucketRateLimiter limiter(String host, int port, long capacity, long refillPerSecond) {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build();
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        connections.afterPropertiesSet();
        connections.start();
        var properties = new IngestProperties(DataSize.ofMegabytes(1), Duration.ofSeconds(1), List.of(),
                new IngestProperties.RateLimit(true, capacity, refillPerSecond));
        return new RedisTokenBucketRateLimiter(new StringRedisTemplate(connections), properties, meters);
    }
}
