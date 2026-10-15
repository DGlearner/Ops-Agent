package io.github.dglearner.opsagent.ingest.publish;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes raw records to {@code logs.raw}, keyed by host so that lines from one host stay in order, and waits until
 * the broker has acknowledged all of them ({@code acks=all}).
 */
@Component
public class RawLogPublisher {

    private final KafkaTemplate<String, RawLogRecord> template;
    private final Duration timeout;

    public RawLogPublisher(KafkaTemplate<String, RawLogRecord> template, IngestProperties properties) {
        this.template = template;
        this.timeout = properties.publishTimeout();
    }

    public void publish(List<RawLogRecord> records) {
        CompletableFuture<?>[] sends;
        try {
            sends = records.stream()
                    .map(r -> template.send(Topics.LOGS_RAW, r.host(), r))
                    .toArray(CompletableFuture[]::new);
        } catch (KafkaException e) {
            // send() blocks up to max.block.ms for metadata and fails synchronously when the cluster is unreachable
            throw new PublishFailedException("Kafka is unavailable", e);
        }
        try {
            CompletableFuture.allOf(sends).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new PublishFailedException("Kafka rejected a record", e.getCause());
        } catch (TimeoutException e) {
            throw new PublishFailedException("Kafka did not acknowledge within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while waiting for Kafka", e);
        }
    }
}
