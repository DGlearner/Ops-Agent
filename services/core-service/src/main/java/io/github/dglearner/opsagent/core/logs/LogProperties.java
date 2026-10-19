package io.github.dglearner.opsagent.core.logs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code ops.logs} (docs/project-design.md §8.7).
 *
 * @param retention how long stored lines are kept, by the day they arrived; as long as {@code logs.normalized} keeps
 *                  them, so a replay from Kafka can fill what was dropped
 * @param maxRange  the widest time range one search may cover
 */
@ConfigurationProperties("ops.logs")
public record LogProperties(@DefaultValue("3d") Duration retention, @DefaultValue("24h") Duration maxRange) {
}
