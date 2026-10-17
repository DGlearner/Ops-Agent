package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.Topics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * Shared by core-service's integration tests: one Kafka and one PostgreSQL for the whole test run (and so one Spring
 * context), and helpers to publish alerts and call the API. Tests share the database, so each uses its own names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    // Incidents are analysed soon after their last alert, and the agent (absent here) is not waited for long.
    "ops.agent.settle-after=2s", "ops.agent.scan-interval=1s", "ops.agent.timeout=5s"})
abstract class CoreTestSupport {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(640L * 1024 * 1024));

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(256L * 1024 * 1024));

    static final KafkaProducer<String, String> PRODUCER;

    static {
        KAFKA.start();
        POSTGRES.start();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.ALERTS, 3, (short) 1),
                    new NewTopic(Topics.ALERTS + ".dlt", 1, (short) 1),
                    new NewTopic(Topics.AGENT_TASKS_HIGH, 3, (short) 1),
                    new NewTopic(Topics.AGENT_RESULTS, 3, (short) 1),
                    new NewTopic(Topics.AGENT_RESULTS + ".dlt", 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the test topics", e);
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        PRODUCER = new KafkaProducer<>(props);
    }

    final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    static void send(Alert alert) throws Exception {
        PRODUCER.send(new ProducerRecord<>(Topics.ALERTS, alert.groupKey(), OpsJson.toJson(alert))).get();
    }

    void awaitStored(UUID alertId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.sql("SELECT count(*) FROM alerts WHERE id = ?").param(alertId).query(Long.class).single() == 1);
    }

    JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return OpsJson.mapper().readTree(response.body());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> put(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** Incidents opened within a day of {@code day}, once there are {@code count} with {@code alerts} in all. */
    List<JsonNode> awaitIncidents(Instant day, int count, int alerts) throws Exception {
        String query = "/api/v1/incidents?from=" + day.minusSeconds(3600) + "&to=" + day.plus(Duration.ofDays(1));
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode page = getJson(query);
            assertThat(page.path("items")).hasSize(count);
            int total = 0;
            for (JsonNode i : page.path("items")) {
                total += i.path("alert_count").asInt();
            }
            assertThat(total).isEqualTo(alerts);
        });
        Thread.sleep(500);
        List<JsonNode> items = new ArrayList<>();
        getJson(query).path("items").forEach(items::add);
        return items;
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }

    static Alert alert(String ruleId, String service, String dependency, Severity severity, Instant at) {
        Map<String, String> groupValues = new LinkedHashMap<>();
        groupValues.put("service", service);
        if (dependency != null) {
            groupValues.put("fields.dependency", dependency);
        }
        String fingerprint = ruleId + "|" + groupValues + "|" + UUID.randomUUID();
        UUID eventId = Ids.newId();
        return new Alert(Ids.nameBased(fingerprint), ruleId, 1, fingerprint, severity, service, service, "kozy",
                groupValues, at.minusSeconds(60), at, 3, 3, at.minusSeconds(1), at, List.of(eventId),
                ruleId + " on " + service, at, List.of(new Alert.Sample(eventId, at, "m", null)));
    }
}
