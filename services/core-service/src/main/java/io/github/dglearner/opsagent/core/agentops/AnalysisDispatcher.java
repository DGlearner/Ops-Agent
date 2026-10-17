package io.github.dglearner.opsagent.core.agentops;

import io.github.dglearner.opsagent.contracts.AgentTask;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Topics;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Sends the task of a requested analysis to the agent, once the request committed. If sending fails, Modulith's
 * registry delivers the event again at the next start; a task already past its deadline by then is not sent.
 */
@Component
class AnalysisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatcher.class);

    private final KafkaOperations<Object, Object> kafka;

    AnalysisDispatcher(KafkaOperations<Object, Object> kafka) {
        this.kafka = kafka;
    }

    @ApplicationModuleListener
    void on(AnalysisRequested requested) throws Exception {
        AgentTask task = requested.task();
        if (task.deadline() != null && task.deadline().isBefore(Instant.now())) {
            log.atInfo().addKeyValue("task_id", task.taskId()).log("Not sending a task past its deadline");
            return;
        }
        kafka.send(Topics.AGENT_TASKS_HIGH, task.taskId().toString(), OpsJson.toJson(task)).get(10, TimeUnit.SECONDS);
        log.atInfo().addKeyValue("task_id", task.taskId()).addKeyValue("incident_id", task.incidentId())
                .addKeyValue("requested_by", task.requestedBy()).log("Analysis task sent to the agent");
    }
}
