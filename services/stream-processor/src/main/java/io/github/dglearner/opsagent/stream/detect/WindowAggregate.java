package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.Alert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Running state of one sliding window for one fingerprint.
 *
 * @param samples the first matches of the window, kept as evidence for the alert (their ids are {@code sampleEventIds})
 * @param latest  the most recent match, which carries the rule snapshot used for threshold and alert
 */
public record WindowAggregate(
        long count,
        Instant firstEventAt,
        Instant lastEventAt,
        List<UUID> sampleEventIds,
        RuleMatch latest,
        List<Alert.Sample> samples) {

    public WindowAggregate {
        sampleEventIds = sampleEventIds == null ? List.of() : sampleEventIds;
        samples = samples == null ? List.of() : samples;
    }

    public static WindowAggregate empty() {
        return new WindowAggregate(0, null, null, List.of(), null, List.of());
    }

    public WindowAggregate add(RuleMatch match, int maxSamples) {
        List<UUID> ids = sampleEventIds;
        List<Alert.Sample> kept = samples;
        if (ids.size() < maxSamples) {
            ids = new ArrayList<>(ids);
            ids.add(match.eventId());
            kept = new ArrayList<>(kept);
            kept.add(new Alert.Sample(match.eventId(), match.eventTime(), match.message(), match.traceId()));
        }
        Instant first = firstEventAt == null || match.eventTime().isBefore(firstEventAt) ? match.eventTime() : firstEventAt;
        Instant last = lastEventAt == null || match.eventTime().isAfter(lastEventAt) ? match.eventTime() : lastEventAt;
        RuleMatch newest = latest == null || !match.eventTime().isBefore(latest.eventTime()) ? match : latest;
        return new WindowAggregate(count + 1, first, last, List.copyOf(ids), newest, List.copyOf(kept));
    }

    public boolean reachedThreshold() {
        return latest != null && count >= latest.threshold();
    }
}
