package io.github.dglearner.opsagent.stream.baseline;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code ops.stream.baseline} (docs/project-design.md §8.8).
 *
 * @param calibration    how long a service is only learned from, after its first line: no alert before
 * @param novelty        how long a template counts as new after it first appeared
 * @param newPatternMin  lines of a new warning or error template, within its novelty, that make an alert
 * @param spikeMin       lines per minute of a known warning or error template below which it never alerts
 * @param spikeSigmas    how far above its baseline (standard deviations) a minute must be to alert
 * @param baselineMinutes the span of the exponentially weighted baseline (alpha = 2 / (n + 1))
 * @param suppressFor    no second spike alert for a template within this time
 * @param similarity     Drain's threshold: the share of equal tokens for a message to join a template
 * @param maxTemplates   templates per service at most; lines of further ones are not counted
 * @param grace          a minute is closed this long after it ended, by stream time
 */
@ConfigurationProperties("ops.stream.baseline")
public record BaselineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30m") Duration calibration,
        @DefaultValue("5m") Duration novelty,
        @DefaultValue("3") int newPatternMin,
        @DefaultValue("5") int spikeMin,
        @DefaultValue("4") double spikeSigmas,
        @DefaultValue("60") int baselineMinutes,
        @DefaultValue("10m") Duration suppressFor,
        @DefaultValue("0.5") double similarity,
        @DefaultValue("500") int maxTemplates,
        @DefaultValue("10s") Duration grace) {

    public static BaselineProperties defaults() {
        return new BaselineProperties(true, Duration.ofMinutes(30), Duration.ofMinutes(5), 3, 5, 4, 60,
                Duration.ofMinutes(10), 0.5, 500, Duration.ofSeconds(10));
    }
}
