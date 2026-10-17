package io.github.dglearner.opsagent.stream.config;

import io.github.dglearner.opsagent.stream.detect.DetectionTime;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code ops.stream}.
 *
 * @param grace           how late an event may arrive and still be counted in its window
 * @param detectionTime   which timestamp drives the windows
 * @param maxSampleEvents event ids kept per alert
 * @param seedRules       publish {@code rules/default-rules.json} to rules.changelog on startup; for local
 *                        development until core-service manages rules (J3)
 * @param quietPeriod     a fingerprint with no matching event for this long has stopped; if it comes back it is a
 *                        new problem and alerts again, even within the suppression of its last alert
 */
@ConfigurationProperties("ops.stream")
public record StreamProperties(
        @DefaultValue("30s") Duration grace,
        @DefaultValue("event") DetectionTime detectionTime,
        @DefaultValue("5") int maxSampleEvents,
        @DefaultValue("false") boolean seedRules,
        @DefaultValue("2m") Duration quietPeriod) {
}
