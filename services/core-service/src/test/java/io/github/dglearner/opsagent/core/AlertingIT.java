package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Alerts from Kafka into PostgreSQL and out through the API, against real Kafka and PostgreSQL. */
class AlertingIT extends CoreTestSupport {

    private static final Instant T0 = Instant.parse("2026-10-17T06:00:00Z");

    @Test
    void storesARedeliveredAlertOnceAndServesItWithItsEvidence() throws Exception {
        Alert alert = alert("shop-database-unavailable", "order-service", Severity.HIGH, T0);
        send(alert);
        send(alert);

        awaitStored(alert.alertId());
        Thread.sleep(1000);
        assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE id = ?").param(alert.alertId()).query(Long.class)
                .single()).isEqualTo(1);

        JsonNode body = getJson("/api/v1/alerts/" + alert.alertId());
        assertThat(body.path("rule_id").asString()).isEqualTo("shop-database-unavailable");
        assertThat(body.path("severity").asString()).isEqualTo("high");
        assertThat(body.path("group_values").path("service").asString()).isEqualTo("order-service");
        assertThat(body.path("samples").path(0).path("message").asString()).isEqualTo("Database unavailable: refused");
        assertThat(body.path("samples").path(0).path("trace_id").asString()).isEqualTo("trace-1");
        assertThat(body.path("window_start").asString()).isEqualTo("2026-10-17T05:59:00Z");
        assertThat(body.has("stored_at")).isTrue();
        assertThat(get("/api/v1/alerts/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void listsNewestFirstWithFiltersAndACursor() throws Exception {
        String service = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        List<Alert> sent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Alert alert = alert("rule-" + (i % 2), service, i == 4 ? Severity.CRITICAL : Severity.MEDIUM,
                    T0.plusSeconds(60L * i));
            sent.add(alert);
            send(alert);
        }
        sent.forEach(a -> awaitStored(a.alertId()));

        JsonNode first = getJson("/api/v1/alerts?service=" + service + "&limit=2");
        assertThat(ids(first)).containsExactly(sent.get(4).alertId(), sent.get(3).alertId());
        JsonNode second = getJson("/api/v1/alerts?service=" + service + "&limit=2&cursor="
                + first.path("next_cursor").asString());
        assertThat(ids(second)).containsExactly(sent.get(2).alertId(), sent.get(1).alertId());
        JsonNode last = getJson("/api/v1/alerts?service=" + service + "&limit=2&cursor="
                + second.path("next_cursor").asString());
        assertThat(ids(last)).containsExactly(sent.get(0).alertId());
        assertThat(last.has("next_cursor")).isFalse();

        assertThat(ids(getJson("/api/v1/alerts?service=" + service + "&severity=critical")))
                .containsExactly(sent.get(4).alertId());
        assertThat(ids(getJson("/api/v1/alerts?service=" + service + "&rule_id=rule-0"))).hasSize(3);
        assertThat(ids(getJson("/api/v1/alerts?service=" + service + "&from=" + T0.plusSeconds(60) + "&to="
                + T0.plusSeconds(180)))).containsExactly(sent.get(2).alertId(), sent.get(1).alertId());

        assertThat(get("/api/v1/alerts?severity=urgent").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/alerts?limit=0").statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/alerts?cursor=garbage").body()).contains("INVALID_CURSOR");
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicAndTheNextOneIsStored() throws Exception {
        PRODUCER.send(new ProducerRecord<>(Topics.ALERTS, "order-service", "{not an alert")).get();
        Alert after = alert("shop-unhandled-errors", "order-service", Severity.HIGH, T0.plusSeconds(600));
        send(after);

        awaitStored(after.alertId());
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(props)) {
            dlt.subscribe(List.of(Topics.ALERTS + ".dlt"));
            List<String> values = new ArrayList<>();
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
                dlt.poll(Duration.ofMillis(500)).forEach(r -> values.add(r.value()));
                return values.contains("{not an alert");
            });
        }
    }

    @Test
    void anAlertArrivingWhileTheDatabaseIsUnavailableIsStoredOnceItIsBack() throws Exception {
        Alert alert = alert("shop-recovery-failing", "payment-service", Severity.MEDIUM, T0.plusSeconds(900));
        var docker = POSTGRES.getDockerClient();
        docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            send(alert);
            Thread.sleep(4000);
        } finally {
            docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }

        awaitStored(alert.alertId());
    }

    private static Alert alert(String ruleId, String service, Severity severity, Instant createdAt) {
        String fingerprint = ruleId + "|service=" + service + "|" + UUID.randomUUID();
        UUID eventId = Ids.newId();
        return new Alert(Ids.nameBased(fingerprint + "|" + createdAt), ruleId, 1, fingerprint, severity, service,
                service, "kozy", Map.of("service", service), createdAt.minusSeconds(60), createdAt, 3, 3,
                createdAt.minusSeconds(2), createdAt, List.of(eventId), "3 failures in " + service, createdAt,
                List.of(new Alert.Sample(eventId, createdAt, "Database unavailable: refused", "trace-1")));
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("alert_id").asString())));
        return ids;
    }

}
