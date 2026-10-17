package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.contracts.AgentResult;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stores the agent's answers from {@link Topics#AGENT_RESULTS}. An unreadable message goes to the dead-letter topic
 * (the error handler of the alerting module applies to every listener); an answer to no waiting analysis is dropped.
 */
@Component
class AgentResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentResultConsumer.class);

    private final Analyses analyses;
    private final MeterRegistry registry;

    AgentResultConsumer(Analyses analyses, MeterRegistry registry) {
        this.analyses = analyses;
        this.registry = registry;
    }

    @KafkaListener(id = "agent-results", topics = Topics.AGENT_RESULTS)
    void consume(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new IllegalArgumentException("Agent result without a value at offset " + record.offset());
        }
        AgentResult result = OpsJson.fromJson(record.value(), AgentResult.class);
        boolean stored = analyses.complete(result);
        registry.counter("ops.core.agent.results", "result", stored ? result.status() : "unexpected").increment();
        log.atInfo().addKeyValue("task_id", result.taskId()).addKeyValue("incident_id", result.incidentId())
                .addKeyValue("status", result.status()).addKeyValue("tool_calls", result.toolCalls())
                .log(stored ? "Agent result stored" : "Agent result for no waiting analysis, dropped");
    }
}
