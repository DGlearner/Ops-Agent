package io.github.dglearner.opsagent.ingest.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under {@code ops.ingest}.
 *
 * @param maxRequestSize   larger request bodies are rejected with 413
 * @param publishTimeout   how long a request waits for Kafka acknowledgements before answering 503
 * @param sources          log sources and the SHA-256 of their API keys; moves to core-service in J3
 */
@ConfigurationProperties("ops.ingest")
public record IngestProperties(
        @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue List<Source> sources,
        @DefaultValue RateLimit rateLimit) {

    /** @param apiKeySha256 lowercase hex SHA-256 of the API key; the key itself is never stored */
    public record Source(String id, String apiKeySha256) {
    }

    /**
     * Token bucket per log source, counted in log lines.
     *
     * @param capacity        largest burst a source can send at once
     * @param refillPerSecond sustained lines per second
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("20000") long capacity,
            @DefaultValue("5000") long refillPerSecond) {
    }
}
