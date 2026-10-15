package io.github.dglearner.opsagent.stream.detect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Running state of one sliding window for one fingerprint.
 *
 * @param latest the most recent match, which carries the rule snapshot used for threshold and alert
 */
public record WindowAggregate(
        long count,
        Instant firstEventAt,
        Instant lastEventAt,
        List<UUID> sampleEventIds,
        RuleMatch latest) {

    public static WindowAggregate empty() {
        return new WindowAggregate(0, null, null, List.of(), null);
    }

    public WindowAggregate add(RuleMatch match, int maxSamples) {
        List<UUID> samples = sampleEventIds;
        if (samples.size() < maxSamples) {
            samples = new ArrayList<>(samples);
            samples.add(match.eventId());
        }
        Instant first = firstEventAt == null || match.eventTime().isBefore(firstEventAt) ? match.eventTime() : firstEventAt;
        Instant last = lastEventAt == null || match.eventTime().isAfter(lastEventAt) ? match.eventTime() : lastEventAt;
        RuleMatch newest = latest == null || !match.eventTime().isBefore(latest.eventTime()) ? match : latest;
        return new WindowAggregate(count + 1, first, last, List.copyOf(samples), newest);
    }

    public boolean reachedThreshold() {
        return latest != null && count >= latest.threshold();
    }
}
