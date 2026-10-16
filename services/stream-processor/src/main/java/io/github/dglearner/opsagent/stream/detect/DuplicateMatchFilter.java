package io.github.dglearner.opsagent.stream.detect;

import java.time.Duration;
import org.apache.kafka.streams.processor.api.FixedKeyProcessor;
import org.apache.kafka.streams.processor.api.FixedKeyProcessorContext;
import org.apache.kafka.streams.processor.api.FixedKeyRecord;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;

/**
 * Drops a match whose event was already counted for the same fingerprint, so one log line delivered twice (a client
 * retrying a batch with the same {@code Idempotency-Key} gets the same {@code event_id}s) is counted once.
 *
 * <p>Exactly-once processing only prevents duplicates that Kafka Streams itself would create; this handles the ones
 * that arrive as separate input records. Only matches are remembered, not every event, and only for {@code retention}
 * (the window plus grace): a copy further apart in detection time can never land in the same window as the original.
 * Input is keyed by fingerprint, so all copies of a match meet in the same task.
 */
public class DuplicateMatchFilter implements FixedKeyProcessor<String, RuleMatch, RuleMatch> {

    private final String storeName;
    private final long retentionMillis;
    private FixedKeyProcessorContext<String, RuleMatch> context;
    private WindowStore<String, Long> seen;

    public DuplicateMatchFilter(String storeName, Duration retention) {
        this.storeName = storeName;
        this.retentionMillis = retention.toMillis();
    }

    @Override
    public void init(FixedKeyProcessorContext<String, RuleMatch> context) {
        this.context = context;
        this.seen = context.getStateStore(storeName);
    }

    @Override
    public void process(FixedKeyRecord<String, RuleMatch> record) {
        RuleMatch match = record.value();
        if (match == null || match.eventId() == null) {
            context.forward(record);
            return;
        }
        String key = record.key() + "|" + match.eventId();
        long time = record.timestamp();
        try (WindowStoreIterator<Long> copies = seen.fetch(key, time - retentionMillis, time + retentionMillis)) {
            if (copies.hasNext()) {
                return;
            }
        }
        seen.put(key, time, time);
        context.forward(record);
    }
}
