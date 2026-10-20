package io.github.dglearner.opsagent.stream.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.stream.detect.DetectionTime;
import io.github.dglearner.opsagent.stream.config.StreamProperties;
import io.github.dglearner.opsagent.stream.parse.ParserRegistry;
import io.github.dglearner.opsagent.stream.topology.OpsTopology;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The baseline detector inside the real topology, fed normalized lines directly (docs/project-design.md §8.8). */
class BaselineDetectorTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final String PAYMENT = "payment-service";

    private TopologyTestDriver driver;
    private TestInputTopic<String, NormalizedEvent> logs;
    private TestOutputTopic<String, Alert> alerts;
    private int seq;

    @BeforeEach
    void start() {
        StreamsBuilder builder = new StreamsBuilder();
        new OpsTopology(ParserRegistry.withDefaultParsers(),
                new StreamProperties(Duration.ofSeconds(30), DetectionTime.EVENT, 5, false, Duration.ofMinutes(2)),
                BaselineProperties.defaults(), Clock.fixed(T0, ZoneOffset.UTC)).addTo(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "baseline-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        driver = new TopologyTestDriver(builder.build(), props);
        logs = driver.createInputTopic(Topics.LOGS_NORMALIZED, Serdes.String().serializer(),
                JsonSerde.of(NormalizedEvent.class).serializer());
        alerts = driver.createOutputTopic(Topics.ALERTS, Serdes.String().deserializer(),
                JsonSerde.of(Alert.class).deserializer());
    }

    @AfterEach
    void close() {
        driver.close();
    }

    @Test
    void aNewWarningAfterCalibrationAlertsOnceAndCarriesItsLines() {
        normalTraffic(0, 40);
        assertThat(alerts.isEmpty()).as("nothing during calibration or normal traffic").isTrue();

        for (int i = 0; i < 5; i++) {
            line(PAYMENT, minute(41, i), Severity.LOW, "Failed to obtain JDBC Connection for order " + (900 + i));
        }
        normalTraffic(42, 3);
        for (int i = 0; i < 5; i++) {
            line(PAYMENT, minute(44, i), Severity.LOW, "Failed to obtain JDBC Connection for order " + (950 + i));
        }
        normalTraffic(45, 2);

        List<Alert> raised = alerts.readValuesToList();
        assertThat(raised).hasSize(1);
        Alert alert = raised.getFirst();
        assertThat(alert.ruleId()).isEqualTo(BaselineDetector.NEW_PATTERN);
        assertThat(alert.service()).isEqualTo(PAYMENT);
        assertThat(alert.severity()).isEqualTo(Severity.LOW);
        assertThat(alert.groupValues()).containsEntry("template", "Failed to obtain JDBC Connection for order <*>")
                .containsEntry("templates", "1");
        assertThat(alert.windowStart()).isEqualTo(T0.plus(Duration.ofMinutes(41)));
        assertThat(alert.count()).isEqualTo(5);
        assertThat(alert.samples()).hasSize(5);
        assertThat(alert.samples().getFirst().message()).isEqualTo("Failed to obtain JDBC Connection for order 900");
        assertThat(alert.summary()).isEqualTo("New warning pattern in payment-service, lines in its first 5 min: "
                + "\"Failed to obtain JDBC Connection for order <*>\" 5");
    }

    @Test
    void aKnownWarningAlertsWhenItSpikesAboveItsBaselineThenQuietsDown() {
        normalTraffic(0, 45);
        for (int i = 0; i < 20; i++) {
            line(PAYMENT, minute(45, i * 2), Severity.LOW, "Retrying charge for order " + i);
        }
        for (int i = 0; i < 20; i++) {
            line(PAYMENT, minute(46, i * 2), Severity.LOW, "Retrying charge for order " + (100 + i));
        }
        normalTraffic(47, 2);

        List<Alert> raised = alerts.readValuesToList();
        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().ruleId()).isEqualTo(BaselineDetector.ERROR_SPIKE);
        assertThat(raised.getFirst().count()).isEqualTo(20);
        assertThat(raised.getFirst().summary()).startsWith("Warning spike in payment-service: "
                + "\"Retrying charge for order <*>\" 20 in 1m, baseline 1.0");
    }

    @Test
    void newPatternsOfOneServiceInOneMinuteAreOneAlertMostFrequentFirst() {
        normalTraffic(0, 40);
        for (int i = 0; i < 6; i++) {
            line(PAYMENT, minute(41, i), Severity.LOW, "Database unavailable: Connection refused");
        }
        for (int i = 0; i < 3; i++) {
            line(PAYMENT, minute(41, 10 + i), Severity.MEDIUM, "Recovery step interrupted charges failed");
        }
        normalTraffic(42, 2);

        List<Alert> raised = alerts.readValuesToList();
        assertThat(raised).hasSize(1);
        Alert alert = raised.getFirst();
        assertThat(alert.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(alert.count()).isEqualTo(9);
        assertThat(alert.groupValues()).containsEntry("templates", "2")
                .containsEntry("template", "Database unavailable: Connection refused");
        assertThat(alert.summary()).isEqualTo("New error patterns in payment-service, lines in their first 5 min: "
                + "\"Database unavailable: Connection refused\" 6, \"Recovery step interrupted charges failed\" 3");
        assertThat(alert.samples()).extracting(Alert.Sample::message).startsWith(
                "Database unavailable: Connection refused", "Recovery step interrupted charges failed");
    }

    @Test
    void anIncidentDoesNotRaiseTheBaselineThatTheNextOneIsMeasuredAgainst() {
        normalTraffic(0, 40);
        for (int i = 0; i < 30; i++) {
            line(PAYMENT, minute(41, i), Severity.LOW, "Charge outcome unknown: HTTP 503 SERVICE_UNAVAILABLE");
        }
        normalTraffic(42, 15);
        for (int i = 0; i < 30; i++) {
            line(PAYMENT, minute(57, i), Severity.LOW, "Charge outcome unknown: HTTP 503 SERVICE_UNAVAILABLE");
        }
        normalTraffic(58, 2);

        assertThat(alerts.readValuesToList()).extracting(Alert::ruleId)
                .containsExactly(BaselineDetector.NEW_PATTERN, BaselineDetector.ERROR_SPIKE);
    }

    @Test
    void ordinaryLinesNeverAlertHoweverManyAndAWarningLearnedInCalibrationIsNotNew() {
        normalTraffic(0, 40);
        for (int i = 0; i < 600; i++) {
            line(PAYMENT, minute(40, i / 10), Severity.INFO, "Payment " + i + " succeeded");
        }
        normalTraffic(41, 3);

        assertThat(alerts.isEmpty()).isTrue();
    }

    @Test
    void aSilentServicesLastMinuteIsClosedByStreamTime() {
        normalTraffic(0, 40);
        for (int i = 0; i < 4; i++) {
            line("inventory-service", minute(40, i), Severity.MEDIUM, "Inventory database pool exhausted");
        }
        // Only other services from here on: the minute closes on stream time.
        for (int m = 41; m < 43; m++) {
            line("order-service", minute(m, 0), Severity.INFO, "Order created");
        }

        assertThat(alerts.isEmpty()).as("inventory-service was not seen before: it is still calibrating").isTrue();
    }

    @Test
    void theModelSurvivesTheStoreRoundTrip() {
        ServiceModel model = new ServiceModel(List.of(new Drain.Template("t1", List.of("Payment", "<*>", "ok"))),
                Map.of("t1", new ServiceModel.Stats("low", 1.5, 0.25, 10, 12, 3, true, 20)), 5, 13,
                Map.of("t1", new ServiceModel.Bucket(2, "vps", T0, T0.plusSeconds(1),
                        List.of(new Alert.Sample(UUID.randomUUID(), T0, "Payment 1 ok", "tr")))));
        JsonSerde<ServiceModel> serde = JsonSerde.of(ServiceModel.class);

        assertThat(serde.deserializer().deserialize("t", serde.serializer().serialize("t", model))).isEqualTo(model);
    }

    /** Every 10 s an ordinary line, and one warning a minute: what the service does when all is well. */
    private void normalTraffic(int fromMinute, int minutes) {
        for (int m = fromMinute; m < fromMinute + minutes; m++) {
            for (int s = 0; s < 60; s += 10) {
                line(PAYMENT, minute(m, s), Severity.INFO, "Payment " + (m * 100 + s) + " succeeded");
            }
            line(PAYMENT, minute(m, 30), Severity.LOW, "Retrying charge for order " + (m * 100));
        }
    }

    private static Instant minute(int m, int second) {
        return T0.plus(Duration.ofMinutes(m)).plusSeconds(second);
    }

    private void line(String service, Instant at, Severity severity, String message) {
        NormalizedEvent e = new NormalizedEvent(UUID.nameUUIDFromBytes(("e" + seq++).getBytes()), "src",
                SourceType.APP_SERVICE, null, "vps", service, null, "app_log", severity, at, at, message, Map.of(),
                List.of());
        logs.pipeInput(service, e, at);
    }
}
