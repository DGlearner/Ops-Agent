package io.github.dglearner.opsagent.stream;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Topics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** The whole application against a real broker: exactly-once processing, rule seeding and the dead-letter topic. */
@Testcontainers
@SpringBootTest(properties = "ops.stream.seed-rules=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StreamProcessorIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2")
            // The image defaults to a 1 GB heap; a test broker needs a fraction of that.
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(640L * 1024 * 1024));

    /**
     * Not a JUnit {@code @TempDir}: that is deleted after the class while the cached Spring context, and with it Kafka
     * Streams, may still be running. The context is closed after the class instead.
     */
    private static final Path STATE_DIR = createStateDir();

    private static final DateTimeFormatter SYSLOG =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    @DynamicPropertySource
    static void streams(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.streams.state-dir", STATE_DIR::toString);
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(Stream.of(Topics.LOGS_RAW, Topics.LOGS_NORMALIZED, Topics.LOGS_UNPARSED,
                            Topics.ALERTS, Topics.RULES_CHANGELOG, "stream-processor.dlt")
                    .map(t -> new NewTopic(t, 3, (short) 1)).toList()).all().get();
        }
    }

    @Test
    void sshBruteForceBecomesOneAlert() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers",
                KAFKA.getBootstrapServers()), new StringSerializer(), JsonSerde.of(RawLogRecord.class).serializer())) {
            for (int i = 0; i < 6; i++) {
                Instant at = start.plusSeconds(i);
                String line = SYSLOG.format(at) + " app-01 sshd[7]: Failed password for root from 203.0.113.50 port "
                        + (40000 + i) + " ssh2";
                producer.send(new ProducerRecord<>(Topics.LOGS_RAW, "app-01", new RawLogRecord(Ids.newId(), null,
                        "it", SourceType.LINUX, "app-01", "sshd", "/var/log/auth.log", line, at)));
            }
        }

        List<Alert> alerts = consume(Topics.ALERTS, JsonSerde.of(Alert.class).deserializer(),
                a -> a.ruleId().equals("ssh-brute-force"), 1, Duration.ofSeconds(60));

        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.groupValues()).containsEntry("fields.src_ip", "203.0.113.50");
            assertThat(alert.count()).isEqualTo(5);
        });
        // The sixth event is inside the suppression period: still one alert.
        assertThat(consume(Topics.ALERTS, JsonSerde.of(Alert.class).deserializer(),
                a -> a.ruleId().equals("ssh-brute-force"), 2, Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    void undeserializableRecordsGoToTheDeadLetterTopic() {
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers",
                KAFKA.getBootstrapServers()), new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(Topics.LOGS_RAW, "bad", "not json".getBytes(StandardCharsets.UTF_8)));
        }

        List<byte[]> dead = consume("stream-processor.dlt", new ByteArrayDeserializer(),
                v -> new String(v, StandardCharsets.UTF_8).equals("not json"), 1, Duration.ofSeconds(60));

        assertThat(dead).hasSize(1);
    }

    private static Path createStateDir() {
        try {
            return Files.createTempDirectory("ops-stream-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> List<T> consume(String topic, Deserializer<T> deserializer, Predicate<T> filter, int expected,
            Duration timeout) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                // Only read results of committed Kafka Streams transactions.
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        List<T> values = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), deserializer)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (values.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, T> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && filter.test(record.value())) {
                        values.add(record.value());
                    }
                }
            }
        }
        return values;
    }
}
