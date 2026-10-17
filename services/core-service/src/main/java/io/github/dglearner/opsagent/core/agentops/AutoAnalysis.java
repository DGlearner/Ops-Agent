package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.core.incident.Incidents;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks the agent about incidents that stopped growing: no new alert for {@code ops.agent.settle-after}, so it sees
 * the whole incident rather than its first alert. Also marks analyses the agent did not answer in time.
 */
@Component
class AutoAnalysis {

    private static final Logger log = LoggerFactory.getLogger(AutoAnalysis.class);

    private final Analyses analyses;
    private final Incidents incidents;
    private final AgentProperties properties;

    AutoAnalysis(Analyses analyses, Incidents incidents, AgentProperties properties) {
        this.analyses = analyses;
        this.incidents = incidents;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ops.agent.scan-interval:15s}", initialDelayString = "${ops.agent.scan-interval:15s}")
    void scan() {
        int expired = analyses.expire();
        if (expired > 0) {
            log.atWarn().addKeyValue("count", expired).log("Analyses timed out waiting for the agent");
        }
        if (!properties.autoAnalyze()) {
            return;
        }
        Instant settled = Instant.now().minus(properties.settleAfter());
        for (Incidents.Settled incident : incidents.settled(settled.minus(properties.autoWindow()), settled)) {
            try {
                analyses.requestIfDue(incident);
            } catch (RuntimeException e) {
                log.atWarn().addKeyValue("incident_id", incident.incidentId()).setCause(e)
                        .log("Cannot request an analysis");
            }
        }
    }
}
