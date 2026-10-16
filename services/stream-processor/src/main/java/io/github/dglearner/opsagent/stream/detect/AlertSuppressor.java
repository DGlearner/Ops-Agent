package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.Alert;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Turns window updates that reached the threshold into alerts, at most one per fingerprint per {@code suppress_for}.
 *
 * <p>Sliding windows emit an update for every event, and several overlapping windows cross the threshold for one
 * burst; without this step one burst would produce many alerts. The suppression deadline is kept in a state store
 * that is committed together with the input offsets ({@code exactly_once_v2}), so restarts and replays do not
 * re-alert. Expired deadlines are purged on stream time.
 *
 * <p>Deadlines are kept in detection time, the record timestamp that also drives the windows and stream time: the
 * log's own time with {@link DetectionTime#EVENT}, the receive time with {@link DetectionTime#INGEST}. The time
 * written in the log line stays in the alert as evidence only.
 */
public class AlertSuppressor implements Processor<Windowed<String>, WindowAggregate, String, Alert> {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(5);

    private final String storeName;
    private final Clock clock;
    private ProcessorContext<String, Alert> context;
    private KeyValueStore<String, Long> suppressedUntil;

    public AlertSuppressor(String storeName, Clock clock) {
        this.storeName = storeName;
        this.clock = clock;
    }

    @Override
    public void init(ProcessorContext<String, Alert> context) {
        this.context = context;
        this.suppressedUntil = context.getStateStore(storeName);
        context.schedule(PURGE_INTERVAL, PunctuationType.STREAM_TIME, this::purgeExpired);
    }

    @Override
    public void process(Record<Windowed<String>, WindowAggregate> record) {
        WindowAggregate agg = record.value();
        if (agg == null || !agg.reachedThreshold()) {
            return;
        }
        String fingerprint = record.key().key();
        long detectedAt = record.timestamp();
        Long until = suppressedUntil.get(fingerprint);
        if (until != null && detectedAt < until) {
            return;
        }
        Alert alert = AlertFactory.create(fingerprint, record.key().window().startTime(),
                record.key().window().endTime(), agg, clock.instant());
        suppressedUntil.put(fingerprint, detectedAt + agg.latest().suppressFor().toMillis());
        context.forward(new Record<>(alert.groupKey(), alert, record.timestamp()));
    }

    private void purgeExpired(long streamTime) {
        List<String> expired = new ArrayList<>();
        try (KeyValueIterator<String, Long> all = suppressedUntil.all()) {
            while (all.hasNext()) {
                KeyValue<String, Long> entry = all.next();
                if (entry.value == null || entry.value < streamTime) {
                    expired.add(entry.key);
                }
            }
        }
        expired.forEach(suppressedUntil::delete);
    }
}
