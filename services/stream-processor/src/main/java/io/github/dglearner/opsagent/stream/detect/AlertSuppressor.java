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
 * Turns window updates that reached the threshold into alerts, one per problem: repeats are suppressed for the rule's
 * {@code suppress_for} while the problem goes on.
 *
 * <p>Sliding windows emit an update for every event, and several overlapping windows cross the threshold for one
 * burst; without this step one burst would produce many alerts. A problem is a burst of matches for a fingerprint:
 * a fingerprint with no match for {@code quietPeriod} has stopped, and if it comes back it is a new problem that alerts
 * again, even within the suppression of the last alert. Otherwise an unrelated fault minutes later (a flash sale's
 * failing calls to inventory, then an inventory fault) would be hidden by the first one's suppression.
 *
 * <p>The state is kept in a store committed together with the input offsets ({@code exactly_once_v2}), so restarts
 * and replays do not re-alert, and purged on stream time. All times are detection time, the record timestamp that
 * also drives the windows: the log's own time with {@link DetectionTime#EVENT}, the receive time with
 * {@link DetectionTime#INGEST}. The time written in the log line stays in the alert as evidence only.
 */
public class AlertSuppressor implements Processor<Windowed<String>, WindowAggregate, String, Alert> {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(5);

    /**
     * Per fingerprint, in epoch milliseconds: when it last alerted and until when repeats are suppressed (null before
     * the first alert), when it last matched, and when its current burst of matches began.
     */
    public record State(Long alertedAt, Long suppressedUntil, long lastMatchAt, long burstStartedAt) {
    }

    private final String storeName;
    private final Clock clock;
    private final long quietMillis;
    private ProcessorContext<String, Alert> context;
    private KeyValueStore<String, State> states;

    public AlertSuppressor(String storeName, Clock clock, Duration quietPeriod) {
        this.storeName = storeName;
        this.clock = clock;
        this.quietMillis = quietPeriod.toMillis();
    }

    @Override
    public void init(ProcessorContext<String, Alert> context) {
        this.context = context;
        this.states = context.getStateStore(storeName);
        context.schedule(PURGE_INTERVAL, PunctuationType.STREAM_TIME, this::purgeExpired);
    }

    @Override
    public void process(Record<Windowed<String>, WindowAggregate> record) {
        WindowAggregate agg = record.value();
        if (agg == null) {
            return;
        }
        String fingerprint = record.key().key();
        long detectedAt = record.timestamp();
        State state = states.get(fingerprint);
        boolean newBurst = state == null || detectedAt - state.lastMatchAt() >= quietMillis;
        long burstStartedAt = newBurst ? detectedAt : state.burstStartedAt();
        long lastMatchAt = state == null ? detectedAt : Math.max(state.lastMatchAt(), detectedAt);
        Long alertedAt = state == null ? null : state.alertedAt();
        Long until = state == null ? null : state.suppressedUntil();

        boolean repeat = until != null && detectedAt < until && alertedAt >= burstStartedAt;
        if (!agg.reachedThreshold() || repeat) {
            states.put(fingerprint, new State(alertedAt, until, lastMatchAt, burstStartedAt));
            return;
        }
        Alert alert = AlertFactory.create(fingerprint, record.key().window().startTime(),
                record.key().window().endTime(), agg, clock.instant());
        states.put(fingerprint, new State(detectedAt, detectedAt + agg.latest().suppressFor().toMillis(), lastMatchAt,
                burstStartedAt));
        context.forward(new Record<>(alert.groupKey(), alert, record.timestamp()));
    }

    /** A fingerprint that went quiet and is past its suppression has nothing left to remember. */
    private void purgeExpired(long streamTime) {
        List<String> expired = new ArrayList<>();
        try (KeyValueIterator<String, State> all = states.all()) {
            while (all.hasNext()) {
                KeyValue<String, State> entry = all.next();
                State s = entry.value;
                if (s == null || (s.suppressedUntil() == null || s.suppressedUntil() < streamTime)
                        && streamTime - s.lastMatchAt() >= quietMillis) {
                    expired.add(entry.key);
                }
            }
        }
        expired.forEach(states::delete);
    }
}
