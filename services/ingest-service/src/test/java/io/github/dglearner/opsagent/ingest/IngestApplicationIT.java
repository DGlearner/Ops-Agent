package io.github.dglearner.opsagent.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.ingest.auth.ApiKeys;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** HTTP request in, records on logs.raw out, against real Kafka and Redis. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestApplicationIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2")
            // The image defaults to a 1 GB heap; a test broker needs a fraction of that.
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(640L * 1024 * 1024));

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(128L * 1024 * 1024));

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry registry) {
        registry.add("ops.ingest.sources[0].id", () -> "it-source");
        registry.add("ops.ingest.sources[0].api-key-sha256", () -> ApiKeys.sha256Hex("it-key"));
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.LOGS_RAW, 6, (short) 1))).all().get();
        }
    }

    @Test
    void publishesEveryLineKeyedByHostAndRetriesKeepEventIds() throws Exception {
        String body = """
                {"source_type":"mysql","host":"db-01","service":"mysql","source_name":"/var/log/mysql/error.log",
                 "logs":["line one","line two","line three"]}
                """;

        HttpResponse<String> first = post(body, "replay-1");
        HttpResponse<String> retry = post(body, "replay-1");

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("\"accepted\":3").isEqualTo(retry.body());

        List<ConsumerRecord<String, RawLogRecord>> records = consume(6);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.key()).isEqualTo("db-01");
            assertThat(r.value().sourceId()).isEqualTo("it-source");
            assertThat(r.value().sourceType()).isEqualTo(SourceType.MYSQL);
        });
        List<RawLogRecord> values = records.stream().map(ConsumerRecord::value).toList();
        assertThat(values.subList(0, 3)).extracting(RawLogRecord::message)
                .containsExactly("line one", "line two", "line three");
        assertThat(values.subList(3, 6)).extracting(RawLogRecord::eventId)
                .containsExactlyElementsOf(values.subList(0, 3).stream().map(RawLogRecord::eventId).toList());
    }

    @Test
    void rejectsUnknownKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", "nope")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void exposesHealth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).contains("\"UP\"");
    }

    @Test
    void documentsTheApiWithSnakeCaseFields() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build();

        String spec = http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        assertThat(spec).contains("\"/api/v1/logs\"", "\"source_type\"", "\"source_name\"", "X-Api-Key")
                .doesNotContain("\"sourceType\"");
    }

    private HttpResponse<String> post(String body, String idempotencyKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/logs"))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", "it-key")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<ConsumerRecord<String, RawLogRecord>> consume(int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, RawLogRecord>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(),
                JsonSerde.of(RawLogRecord.class).deserializer())) {
            consumer.subscribe(List.of(Topics.LOGS_RAW));
            long deadline = System.currentTimeMillis() + 20_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        // All records share a key, so they sit in one partition in send order.
        return records;
    }
}
