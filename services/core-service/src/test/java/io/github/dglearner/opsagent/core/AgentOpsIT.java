package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.AgentResult;
import io.github.dglearner.opsagent.contracts.AgentResult.Evidence;
import io.github.dglearner.opsagent.contracts.AgentResult.Hypothesis;
import io.github.dglearner.opsagent.contracts.AgentTask;
import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.Topics;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The agent's side of an incident, with the test playing the agent: tasks on {@link Topics#AGENT_TASKS_HIGH},
 * answers on {@link Topics#AGENT_RESULTS}. Each test uses its own day, as in {@link IncidentIT}.
 */
class AgentOpsIT extends CoreTestSupport {

    @Test
    void anAnswerIsStoredWithOnlyTheEvidenceTheIncidentHas() throws Exception {
        Instant t = Instant.parse("2027-06-01T10:00:00Z");
        Alert outage = alert("shop-database-unavailable", "order-service", null, Severity.HIGH, t);
        send(outage);
        String incidentId = awaitIncidents(t, 1, 1).getFirst().path("incident_id").asString();

        HttpResponse<String> requested = post("/api/v1/incidents/" + incidentId + "/analyses", "");
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);
        JsonNode pending = OpsJson.mapper().readTree(requested.body());
        assertThat(pending.path("status").asString()).isEqualTo("pending");
        String taskId = pending.path("analysis_id").asString();
        // Asking again while the agent works on it returns the same analysis.
        assertThat(OpsJson.mapper().readTree(post("/api/v1/incidents/" + incidentId + "/analyses", "").body())
                .path("analysis_id").asString()).isEqualTo(taskId);

        AgentTask task = awaitTask(UUID.fromString(taskId));
        assertThat(task.kind()).isEqualTo(AgentTask.INCIDENT_ANALYSIS);
        assertThat(task.incidentId().toString()).isEqualTo(incidentId);
        assertThat(task.language()).isEqualTo("zh");

        Evidence alert = new Evidence("alert", outage.alertId().toString(), "数据库不可用");
        Evidence event = new Evidence("event", outage.sampleEventIds().getFirst().toString(), null);
        Evidence topology = new Evidence("topology", "shop-mysql", "订单服务依赖它");
        Evidence madeUp = new Evidence("alert", UUID.randomUUID().toString(), "没读过的告警");
        answer(new AgentResult(task.taskId(), task.incidentId(), AgentResult.COMPLETED, "MySQL 不可用，下单失败。",
                false, List.of(new Hypothesis("shop-mysql", "MySQL 宕机", "high",
                        List.of(alert, event, topology, madeUp))),
                List.of("检查 shop-mysql 容器"), "test-model", 4, 1200, null, Instant.now()));

        JsonNode analysis = awaitAnalysis(taskId, "completed");
        assertThat(analysis.path("summary").asString()).isEqualTo("MySQL 不可用，下单失败。");
        assertThat(analysis.path("uncertain").asBoolean()).isFalse();
        JsonNode hypothesis = analysis.path("hypotheses").get(0);
        assertThat(hypothesis.path("component").asString()).isEqualTo("shop-mysql");
        assertThat(hypothesis.path("evidence").findValuesAsString("id")).containsExactly(alert.id(), event.id(),
                "shop-mysql");
        assertThat(analysis.path("rejected_evidence").findValuesAsString("id")).containsExactly(madeUp.id());
        assertThat(analysis.path("tool_calls").asInt()).isEqualTo(4);
        assertThat(getJson("/api/v1/incidents/" + incidentId + "/analyses").findValuesAsString("analysis_id"))
                .contains(taskId);

        // A second answer to the same task, or one for a task never asked, changes nothing.
        answer(new AgentResult(task.taskId(), task.incidentId(), AgentResult.FAILED, null, true, null, null, null, 0,
                0, "again", Instant.now()));
        answer(new AgentResult(UUID.randomUUID(), task.incidentId(), AgentResult.COMPLETED, "?", true, null, null,
                null, 0, 0, null, Instant.now()));
        Thread.sleep(1500);
        assertThat(getJson("/api/v1/analyses/" + taskId).path("status").asString()).isEqualTo("completed");
        assertThat(get("/api/v1/analyses/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(post("/api/v1/incidents/" + UUID.randomUUID() + "/analyses", "").statusCode()).isEqualTo(404);
    }

    @Test
    void aQuietIncidentIsAnalysedAutomaticallyAndAgainAfterNewAlerts() throws Exception {
        Instant t = Instant.parse("2027-07-01T10:00:00Z");
        send(alert("shop-dependency-call-failures", "order-service", "payment-service", Severity.MEDIUM, t));
        String incidentId = awaitIncidents(t, 1, 1).getFirst().path("incident_id").asString();

        JsonNode first = awaitAnalyses(incidentId, 1).getFirst();
        assertThat(first.path("requested_by").asString()).isEqualTo("system");
        AgentTask task = awaitTask(UUID.fromString(first.path("analysis_id").asString()));
        answer(new AgentResult(task.taskId(), task.incidentId(), AgentResult.COMPLETED, "支付服务调用失败。", false,
                List.of(new Hypothesis("payment-service", "支付服务异常", "medium",
                        List.of(new Evidence("incident", incidentId, null)))),
                null, "test-model", 2, 800, null, Instant.now()));
        awaitAnalysis(task.taskId().toString(), "completed");

        send(alert("shop-dependency-call-failures", "order-service", "payment-service", Severity.MEDIUM,
                t.plusSeconds(90)));
        List<JsonNode> both = awaitAnalyses(incidentId, 2);
        assertThat(both.getFirst().path("requested_by").asString()).isEqualTo("system");
        assertThat(both.getFirst().path("analysis_id").asString()).isNotEqualTo(task.taskId().toString());
    }

    @Test
    void anAnalysisTheAgentDoesNotAnswerTimesOutAndALateAnswerIsStillKept() throws Exception {
        Instant t = Instant.parse("2027-08-01T10:00:00Z");
        send(alert("shop-unhandled-errors", "inventory-service", null, Severity.HIGH, t));
        String incidentId = awaitIncidents(t, 1, 1).getFirst().path("incident_id").asString();

        String taskId = awaitAnalyses(incidentId, 1).getFirst().path("analysis_id").asString();
        awaitAnalysis(taskId, "timed_out");

        answer(new AgentResult(UUID.fromString(taskId), UUID.fromString(incidentId), AgentResult.COMPLETED,
                "库存服务内部错误。", false, List.of(new Hypothesis("inventory-service", "未处理的异常", "low",
                        List.of(new Evidence("alert", UUID.randomUUID().toString(), null)))),
                null, "test-model", 1, 9000, null, Instant.now()));
        JsonNode late = awaitAnalysis(taskId, "completed");
        // Its only evidence was made up, so it cannot be taken as certain.
        assertThat(late.path("uncertain").asBoolean()).isTrue();
        assertThat(late.path("hypotheses").get(0).path("evidence")).isEmpty();
    }

    private static void answer(AgentResult result) throws Exception {
        PRODUCER.send(new ProducerRecord<>(Topics.AGENT_RESULTS, result.taskId().toString(), OpsJson.toJson(result)))
                .get();
    }

    private static AgentTask awaitTask(UUID taskId) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(), "key.deserializer", StringDeserializer.class,
                "value.deserializer", StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(Topics.AGENT_TASKS_HIGH).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<AgentTask> found = new ArrayList<>();
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(taskId.toString())) {
                        found.add(OpsJson.fromJson(record.value(), AgentTask.class));
                    }
                }
                return !found.isEmpty();
            });
            assertThat(found).hasSize(1);
            return found.getFirst();
        }
    }

    private JsonNode awaitAnalysis(String analysisId, String status) {
        JsonNode[] analysis = new JsonNode[1];
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            analysis[0] = getJson("/api/v1/analyses/" + analysisId);
            assertThat(analysis[0].path("status").asString()).isEqualTo(status);
        });
        return analysis[0];
    }

    /** The incident's analyses, newest first, once there are {@code count}. */
    private List<JsonNode> awaitAnalyses(String incidentId, int count) {
        List<JsonNode> analyses = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            analyses.clear();
            getJson("/api/v1/incidents/" + incidentId + "/analyses").forEach(analyses::add);
            assertThat(analyses).hasSize(count);
        });
        return analyses;
    }
}
