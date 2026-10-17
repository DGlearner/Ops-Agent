package io.github.dglearner.opsagent.core.alerting;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;

/** Which alerts to list; null means any. {@code from} is inclusive, {@code to} exclusive, both on creation time. */
public record AlertFilter(Severity severity, String service, String ruleId, Instant from, Instant to) {
}
