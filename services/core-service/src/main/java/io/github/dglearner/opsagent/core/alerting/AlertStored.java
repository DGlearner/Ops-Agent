package io.github.dglearner.opsagent.core.alerting;

import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An alert was stored for the first time. Published in the storing transaction, through Modulith's registry. */
public record AlertStored(UUID alertId, String ruleId, Severity severity, String service, String host,
        Map<String, String> groupValues, String summary, Instant createdAt) {
}
