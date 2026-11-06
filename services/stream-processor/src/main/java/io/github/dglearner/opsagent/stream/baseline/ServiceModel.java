package io.github.dglearner.opsagent.stream.baseline;

import io.github.dglearner.opsagent.contracts.Alert;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the baseline detector knows about one service, as kept in its state store: the templates, each template's
 * baseline, and the minute being counted.
 *
 * @param firstMinute   the first minute seen (epoch minutes): the calibration runs from there
 * @param currentMinute the minute being counted
 * @param counts        this minute's lines per template id
 */
public record ServiceModel(List<Drain.Template> templates, Map<String, Stats> stats, long firstMinute,
        long currentMinute, Map<String, Bucket> counts) {

    /**
     * One template's baseline: an exponentially weighted mean and variance of its lines per minute, the most severe
     * level it was seen at, and the alert state.
     *
     * @param firstSeen     the minute it first appeared
     * @param novel         lines while it is new (the first minutes after it appeared)
     * @param patternAlerted whether its appearance was alerted already
     * @param spikeUntil    no further spike alert before this minute
     */
    public record Stats(String severity, double mean, double variance, long firstSeen, long lastSeen, long novel,
            boolean patternAlerted, long spikeUntil) {
    }

    /** One template's lines in the current minute, with the first few as evidence. */
    public record Bucket(long count, String host, Instant firstAt, Instant lastAt, List<Alert.Sample> samples) {
    }
}
