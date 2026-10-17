package io.github.dglearner.opsagent.core.agentops;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code ops.agent}.
 *
 * @param autoAnalyze     ask the agent about every incident once it has had no new alert for {@code settleAfter}
 * @param settleAfter     how long an incident must be quiet before it is analysed, so the agent sees all of it
 * @param autoWindow      only incidents whose latest alert arrived within this time are analysed automatically; older
 *                        ones (found at startup after a pause, say) are history, analysed only when someone asks
 * @param timeout         how long to wait for the agent's answer before the analysis is marked timed out
 * @param maxAutoAnalyses at most this many automatic analyses per incident: again only after new alerts arrived
 * @param language        language of the agent's summaries
 */
@ConfigurationProperties("ops.agent")
public record AgentProperties(
        @DefaultValue("true") boolean autoAnalyze,
        @DefaultValue("60s") Duration settleAfter,
        @DefaultValue("1h") Duration autoWindow,
        @DefaultValue("3m") Duration timeout,
        @DefaultValue("3") int maxAutoAnalyses,
        @DefaultValue("zh") String language) {
}
