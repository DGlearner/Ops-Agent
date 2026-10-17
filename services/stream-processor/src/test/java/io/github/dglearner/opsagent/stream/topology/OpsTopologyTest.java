package io.github.dglearner.opsagent.stream.topology;

import static io.github.dglearner.opsagent.stream.Datasets.raw;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.contracts.UnparsedLog;
import io.github.dglearner.opsagent.stream.Datasets;
import io.github.dglearner.opsagent.stream.config.StreamProperties;
import io.github.dglearner.opsagent.stream.detect.DetectionTime;
import io.github.dglearner.opsagent.stream.parse.ParserRegistry;
import io.github.dglearner.opsagent.stream.rules.DefaultRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OpsTopologyTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final DateTimeFormatter SYSLOG =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private TopologyTestDriver driver;
    private TestInputTopic<String, RawLogRecord> raw;
    private TestInputTopic<String, RuleDefinition> rules;
    private TestOutputTopic<String, NormalizedEvent> normalized;
    private TestOutputTopic<String, UnparsedLog> unparsed;
    private TestOutputTopic<String, Alert> alerts;

    @AfterEach
    void close() {
        if (driver != null) {
            driver.close();
        }
    }

    @Test
    void routesParsedAndUnparsedRecords() {
        start(DetectionTime.EVENT);

        raw.pipeInput("web-01", raw(SourceType.NGINX, "web-01", "nginx",
                "198.51.100.23 - - [01/Mar/2026:10:00:00 +0000] \"GET / HTTP/1.1\" 200 1 \"-\" \"curl\""));
        raw.pipeInput("web-01", raw(SourceType.NGINX, "web-01", "nginx", "garbage"));

        var event = normalized.readKeyValue();
        assertThat(event.key).isEqualTo("nginx");
        assertThat(event.value.eventType()).isEqualTo("http_request");
        var failed = unparsed.readKeyValue();
        assertThat(failed.key).isEqualTo("nginx");
        assertThat(failed.value.reason()).isEqualTo("no nginx pattern matched");
        assertThat(normalized.isEmpty()).isTrue();
    }

    @Test
    void alertsOnceWhenTheThresholdIsReached() {
        start(DetectionTime.EVENT);
        rules.pipeInput("ssh", sshRule(3, true));

        for (int i = 0; i < 6; i++) {
            sshFailure(T0.plusSeconds(10L * i), "198.51.100.7");
        }

        List<Alert> out = alerts.readValuesToList();
        assertThat(out).hasSize(1);
        Alert alert = out.getFirst();
        assertThat(alert.ruleId()).isEqualTo("ssh");
        assertThat(alert.count()).isEqualTo(3);
        assertThat(alert.fingerprint()).isEqualTo("ssh|host=app-01|fields.src_ip=198.51.100.7");
        assertThat(alert.groupValues()).containsEntry("fields.src_ip", "198.51.100.7");
        assertThat(alert.groupKey()).isEqualTo("sshd");
        assertThat(alert.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(alert.firstEventAt()).isEqualTo(T0);
        assertThat(alert.lastEventAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(alert.sampleEventIds()).hasSize(3);
        assertThat(alert.samples()).hasSize(3).first().satisfies(sample -> {
            assertThat(sample.eventId()).isEqualTo(alert.sampleEventIds().getFirst());
            assertThat(sample.timestamp()).isEqualTo(T0);
            assertThat(sample.message()).contains("Failed password for root from 198.51.100.7");
        });
        assertThat(alert.summary()).isEqualTo("3 failures from 198.51.100.7 on app-01 in 5m");
        assertThat(alert.createdAt()).isEqualTo(NOW);
    }

    @Test
    void separatesGroupsAndAlertsAgainAfterSuppression() {
        start(DetectionTime.EVENT);
        rules.pipeInput("ssh", sshRule(3, true));

        for (int i = 0; i < 3; i++) {
            sshFailure(T0.plusSeconds(i), "198.51.100.7");
            sshFailure(T0.plusSeconds(i), "198.51.100.8");
        }
        // suppress_for is 10 minutes
        for (int i = 0; i < 3; i++) {
            sshFailure(T0.plusSeconds(11 * 60 + i), "198.51.100.7");
        }

        Map<String, Long> perSource = alerts.readValuesToList().stream()
                .collect(Collectors.groupingBy(a -> a.groupValues().get("fields.src_ip"), Collectors.counting()));
        assertThat(perSource).containsExactlyInAnyOrderEntriesOf(Map.of("198.51.100.7", 2L, "198.51.100.8", 1L));
    }

    @Test
    void followsRuleChangesWithoutRestart() {
        start(DetectionTime.EVENT);
        rules.pipeInput("ssh", sshRule(3, false));
        for (int i = 0; i < 3; i++) {
            sshFailure(T0.plusSeconds(i), "198.51.100.7");
        }
        assertThat(alerts.isEmpty()).as("disabled rule").isTrue();

        rules.pipeInput("ssh", sshRule(2, true));
        sshFailure(T0.plusSeconds(10), "198.51.100.9");
        sshFailure(T0.plusSeconds(11), "198.51.100.9");
        assertThat(alerts.readValuesToList()).extracting(Alert::ruleVersion).containsExactly(2);

        rules.pipeInput("ssh", (RuleDefinition) null);
        sshFailure(T0.plusSeconds(20), "198.51.100.10");
        sshFailure(T0.plusSeconds(21), "198.51.100.10");
        assertThat(alerts.isEmpty()).as("deleted rule").isTrue();
    }

    @Test
    void dropsEventsLaterThanTheGracePeriod() {
        start(DetectionTime.EVENT);
        rules.pipeInput("ssh", sshRule(2, true));

        replayOldEventsAfterNewerOnes();

        assertThat(alerts.isEmpty()).isTrue();
    }

    @Test
    void ingestTimeModeCountsReplayedOldEvents() {
        start(DetectionTime.INGEST);
        rules.pipeInput("ssh", sshRule(2, true));

        replayOldEventsAfterNewerOnes();

        assertThat(alerts.readValuesToList()).extracting(a -> a.groupValues().get("fields.src_ip"))
                .containsExactly("198.51.100.7");
    }

    @ParameterizedTest
    @EnumSource(DetectionTime.class)
    void countsAnEventDeliveredMoreThanOnceOnce(DetectionTime detectionTime) {
        start(detectionTime);
        rules.pipeInput("ssh", sshRule(3, true));

        // One batch retried twice with the same Idempotency-Key: same event id, each copy received later.
        UUID eventId = Ids.newId();
        for (int i = 0; i < 3; i++) {
            sshFailure(eventId, T0, T0.plusSeconds(1 + 5L * i), "198.51.100.7");
        }
        assertThat(alerts.isEmpty()).as("one event, three copies").isTrue();

        sshFailure(T0.plusSeconds(20), "198.51.100.7");
        sshFailure(T0.plusSeconds(21), "198.51.100.7");
        assertThat(alerts.readValuesToList()).singleElement().satisfies(alert ->
                assertThat(alert.count()).isEqualTo(3));
    }

    @Test
    void ingestTimeModeSuppressesForTheSpanOfReceiveTime() {
        start(DetectionTime.INGEST);
        rules.pipeInput("ssh", sshRule(3, true));
        // An old dataset replayed a month later, faster than it was written: 20 log minutes per 6 received ones.
        Instant received = T0.plus(Duration.ofDays(30));

        burst(T0, received);
        burst(T0.plus(Duration.ofMinutes(20)), received.plus(Duration.ofMinutes(6)));
        burst(T0.plus(Duration.ofMinutes(40)), received.plus(Duration.ofMinutes(12)));

        // suppress_for is 10 minutes of receive time: the second burst is suppressed, the third alerts again.
        assertThat(alerts.readValuesToList()).hasSize(2);
    }

    @Test
    void shopDatabaseOutageAlertsOncePerServiceWhileSellingOutDoesNot() {
        start(DetectionTime.EVENT);
        DefaultRules.load().forEach(rule -> rules.pipeInput(rule.ruleId(), rule));

        for (int i = 0; i < 60; i++) {
            shopLog("inventory-service", T0.plusMillis(500L * i), "INFO", "reservation_rejected",
                    "\"code\":\"INSUFFICIENT_STOCK\",\"sku\":\"SKU-2001\"");
        }
        for (int i = 0; i < 5; i++) {
            shopLog("order-service", T0.plusSeconds(40 + i), "WARN", "database_unavailable",
                    "\"error_type\":\"CannotGetJdbcConnectionException\"");
            shopLog("inventory-service", T0.plusSeconds(40 + i), "WARN", "database_unavailable",
                    "\"error_type\":\"CannotGetJdbcConnectionException\"");
        }

        List<Alert> out = alerts.readValuesToList();
        assertThat(out).allSatisfy(alert -> assertThat(alert.samples()).hasSize(3).allSatisfy(sample -> {
            assertThat(sample.message()).isEqualTo("database_unavailable seen");
            assertThat(sample.traceId()).isEqualTo("trace-" + alert.service());
        }));
        assertThat(out)
                .extracting(Alert::ruleId, Alert::service)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("shop-database-unavailable", "order-service"),
                        Tuple.tuple("shop-database-unavailable", "inventory-service"));
    }

    @Test
    void sampleDatasetRaisesTheExpectedAlerts() {
        start(DetectionTime.EVENT);
        DefaultRules.load().forEach(rule -> rules.pipeInput(rule.ruleId(), rule));

        for (RawLogRecord record : Datasets.load("aiops_mixed_incident_small.jsonl")) {
            raw.pipeInput(record.host(), record);
        }

        List<Alert> out = alerts.readValuesToList();
        assertThat(out).extracting(Alert::ruleId).containsExactlyInAnyOrder(
                "ssh-brute-force",
                "mysql-connection-refused",
                "mysql-too-many-connections",
                "app-db-connection-errors",
                "nginx-5xx-burst",
                "container-oom-killed",
                "container-restart-loop");
        Map<String, Alert> byRule = out.stream().collect(Collectors.toMap(Alert::ruleId, a -> a));
        assertThat(byRule.get("ssh-brute-force").groupValues()).containsEntry("fields.src_ip", "198.51.100.240");
        assertThat(byRule.get("app-db-connection-errors").service()).isEqualTo("payment-api");
        assertThat(byRule.get("nginx-5xx-burst").host()).isEqualTo("web-01");
        assertThat(byRule.get("container-restart-loop").summary())
                .isEqualTo("Container api-gateway.1 on app-02 restarted 3 times within 15m");
    }

    /** Stream time first moves an hour ahead; then two events from T0 arrive, as when an old file is replayed. */
    private void replayOldEventsAfterNewerOnes() {
        Instant later = T0.plusSeconds(3600);
        sshFailure(later, "198.51.100.1");
        sshFailure(T0, later.plusSeconds(2), "198.51.100.7");
        sshFailure(T0.plusSeconds(1), later.plusSeconds(3), "198.51.100.7");
    }

    private void start(DetectionTime detectionTime) {
        StreamsBuilder builder = new StreamsBuilder();
        new OpsTopology(ParserRegistry.withDefaultParsers(),
                new StreamProperties(Duration.ofSeconds(30), detectionTime, 5, false),
                Clock.fixed(NOW, ZoneOffset.UTC)).addTo(builder);
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "ops-topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
        driver = new TopologyTestDriver(builder.build(), props);
        raw = driver.createInputTopic(Topics.LOGS_RAW, OpsTopology.STRING.serializer(), OpsTopology.RAW.serializer());
        rules = driver.createInputTopic(Topics.RULES_CHANGELOG, OpsTopology.STRING.serializer(),
                OpsTopology.RULE.serializer());
        normalized = driver.createOutputTopic(Topics.LOGS_NORMALIZED, OpsTopology.STRING.deserializer(),
                OpsTopology.NORMALIZED.deserializer());
        unparsed = driver.createOutputTopic(Topics.LOGS_UNPARSED, OpsTopology.STRING.deserializer(),
                OpsTopology.UNPARSED.deserializer());
        alerts = driver.createOutputTopic(Topics.ALERTS, OpsTopology.STRING.deserializer(),
                OpsTopology.ALERT.deserializer());
    }

    /** A shop service's ECS JSON log line, shipped as an app_service record. */
    private void shopLog(String service, Instant time, String level, String action, String extraFields) {
        String line = "{\"@timestamp\":\"" + time + "\",\"log\":{\"level\":\"" + level + "\",\"logger\":\"x.Y\"},"
                + "\"service\":{\"name\":\"" + service + "\"},\"message\":\"" + action + " seen\","
                + "\"trace.id\":\"trace-" + service + "\",\"event\":{\"action\":\"" + action + "\"},"
                + extraFields + "}";
        RawLogRecord record = new RawLogRecord(Ids.newId(), null, "test", SourceType.APP_SERVICE, "kozy", service,
                "logs/shop/" + service + ".log", line, time.plusSeconds(1));
        raw.pipeInput("kozy", record, time.plusSeconds(1));
    }

    private void burst(Instant eventTime, Instant receivedAt) {
        for (int i = 0; i < 3; i++) {
            sshFailure(eventTime.plusSeconds(i), receivedAt.plusSeconds(i), "198.51.100.7");
        }
    }

    /** An SSH failure logged at {@code eventTime} and received one second later. */
    private void sshFailure(Instant eventTime, String sourceIp) {
        sshFailure(eventTime, eventTime.plusSeconds(1), sourceIp);
    }

    private void sshFailure(Instant eventTime, Instant receivedAt, String sourceIp) {
        sshFailure(Ids.newId(), eventTime, receivedAt, sourceIp);
    }

    private void sshFailure(UUID eventId, Instant eventTime, Instant receivedAt, String sourceIp) {
        String line = SYSLOG.format(eventTime) + " app-01 sshd[1]: Failed password for root from " + sourceIp
                + " port 22 ssh2";
        RawLogRecord record = new RawLogRecord(eventId, null, "test", SourceType.LINUX, "app-01", "sshd", null,
                line, receivedAt);
        raw.pipeInput("app-01", record, receivedAt);
    }

    private static RuleDefinition sshRule(int threshold, boolean enabled) {
        return new RuleDefinition("ssh", threshold == 2 ? 2 : 1, enabled, "ssh",
                new RuleDefinition.Match(List.of("ssh_login_failed"), null, null, null),
                List.of("host", "fields.src_ip"), Duration.ofMinutes(5), threshold, Severity.MEDIUM,
                Duration.ofMinutes(10), "{count} failures from {fields.src_ip} on {host} in {window}");
    }
}
