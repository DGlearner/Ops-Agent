package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Topics;
import io.github.dglearner.opsagent.core.logs.LogPartitions;
import io.github.dglearner.opsagent.core.logs.Logs;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Log lines from Kafka into PostgreSQL and out through the search API (docs/project-design.md §8.7). */
class LogsIT extends CoreTestSupport {

    // Recent: partitions exist for the days within the retention only.
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(30));

    @Autowired
    Logs logs;

    @Autowired
    LogPartitions partitions;

    @Test
    void storesARedeliveredLineOnceAndFindsItByEveryFilter() throws Exception {
        String service = service();
        NormalizedEvent db = line(service, "database_unavailable", Severity.MEDIUM, T0,
                "Database unavailable: Communications link failure", "trace-db");
        NormalizedEvent ok = line(service, "payment_succeeded", Severity.INFO, T0.plusSeconds(1), "Payment succeeded",
                "trace-ok");
        send(db);
        send(db);
        send(ok);

        awaitLines(service, 2);
        assertThat(jdbc.sql("SELECT count(*) FROM log_events WHERE event_id = ?").param(db.eventId())
                .query(Long.class).single()).isEqualTo(1);

        JsonNode all = search(service, "");
        assertThat(ids(all)).containsExactly(ok.eventId().toString(), db.eventId().toString());
        JsonNode first = all.path("items").path(1);
        assertThat(first.path("message").asString()).isEqualTo("Database unavailable: Communications link failure");
        assertThat(first.path("trace_id").asString()).isEqualTo("trace-db");
        assertThat(first.path("event_type").asString()).isEqualTo("database_unavailable");
        assertThat(first.path("severity").asString()).isEqualTo("medium");
        assertThat(first.path("source_type").asString()).isEqualTo("app_service");
        assertThat(first.path("fields").path("trace.id").asString()).isEqualTo("trace-db");

        assertThat(ids(search(service, "&min_severity=medium"))).containsExactly(db.eventId().toString());
        assertThat(ids(search(service, "&event_type=payment_succeeded"))).containsExactly(ok.eventId().toString());
        assertThat(ids(search(service, "&trace_id=trace-db"))).containsExactly(db.eventId().toString());
        assertThat(ids(search(service, "&q=" + encode("LINK fail")))).containsExactly(db.eventId().toString());
        assertThat(ids(getJson("/api/v1/logs?service=" + service + "&from=" + T0.plusSeconds(1) + "&to="
                + T0.plusSeconds(60)))).containsExactly(ok.eventId().toString());
    }

    @Test
    void aTextIsLookedForLiterally() throws Exception {
        String service = service();
        NormalizedEvent percent = line(service, "app_log", Severity.INFO, T0, "disk 100% full", null);
        NormalizedEvent other = line(service, "app_log", Severity.INFO, T0.plusSeconds(1), "disk 1000 full", null);
        send(percent);
        send(other);
        awaitLines(service, 2);

        assertThat(ids(search(service, "&q=" + encode("100%")))).containsExactly(percent.eventId().toString());
        assertThat(ids(search(service, "&q=" + encode("k_1")))).isEmpty();
    }

    @Test
    void pagesNewestFirstWithACursor() throws Exception {
        String service = service();
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            NormalizedEvent e = line(service, "app_log", Severity.INFO, T0.plusSeconds(i), "line " + i, null);
            sent.addFirst(e.eventId().toString());
            send(e);
        }
        awaitLines(service, 5);

        JsonNode page1 = search(service, "&limit=2");
        JsonNode page2 = search(service, "&limit=2&cursor=" + page1.path("next_cursor").asString());
        JsonNode page3 = search(service, "&limit=2&cursor=" + page2.path("next_cursor").asString());

        List<String> seen = new ArrayList<>(ids(page1));
        seen.addAll(ids(page2));
        seen.addAll(ids(page3));
        assertThat(seen).isEqualTo(sent);
        assertThat(page3.has("next_cursor")).isFalse();
    }

    @Test
    void countsByEventTypeWithTheMostSevereAndAnExample() throws Exception {
        String service = service();
        for (int i = 0; i < 3; i++) {
            send(line(service, "payment_succeeded", Severity.INFO, T0.plusSeconds(i), "Payment " + i + " succeeded",
                    null));
        }
        send(line(service, "recovery_step_failed", Severity.MEDIUM, T0.plusSeconds(5), "Recovery failed", null));
        awaitLines(service, 4);

        JsonNode counts = getJson("/api/v1/logs/counts?service=" + service + "&from=" + T0.minusSeconds(60) + "&to="
                + T0.plusSeconds(60));
        assertThat(counts.size()).isEqualTo(2);
        assertThat(counts.path(0).path("key").asString()).isEqualTo("payment_succeeded");
        assertThat(counts.path(0).path("count").asLong()).isEqualTo(3);
        assertThat(counts.path(0).path("example").asString()).isEqualTo("Payment 2 succeeded");
        assertThat(counts.path(0).path("first_at").asString()).isEqualTo(T0.toString());
        assertThat(counts.path(1).path("key").asString()).isEqualTo("recovery_step_failed");
        assertThat(counts.path(1).path("severity").asString()).isEqualTo("medium");

        JsonNode bySeverity = getJson("/api/v1/logs/counts?by=severity&service=" + service + "&from="
                + T0.minusSeconds(60) + "&to=" + T0.plusSeconds(60));
        assertThat(bySeverity.path(0).path("key").asString()).isEqualTo("info");
    }

    @Test
    void linesOutsideTheRetentionAreDroppedAndOldPartitionsGo() throws Exception {
        String service = service();
        NormalizedEvent old = new NormalizedEvent(UUID.randomUUID(), "src", SourceType.APP_SERVICE, null, "vps",
                service, null, "app_log", Severity.INFO, T0.minus(Duration.ofDays(10)),
                T0.minus(Duration.ofDays(10)), "from long ago", Map.of(), List.of());
        NormalizedEvent now = line(service, "app_log", Severity.INFO, T0, "now", null);
        send(old);
        send(now);
        awaitLines(service, 1);
        assertThat(jdbc.sql("SELECT count(*) FROM log_events WHERE event_id = ?").param(old.eventId())
                .query(Long.class).single()).isZero();

        jdbc.sql("CREATE TABLE log_events_20200101 PARTITION OF log_events FOR VALUES FROM ('2020-01-01 00:00:00+00')"
                + " TO ('2020-01-02 00:00:00+00')").update();
        assertThat(partitions.ensure()).contains("log_events_20200101");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_class WHERE relname = 'log_events_20200101'").query(Long.class)
                .single()).isZero();
    }

    @Test
    void anEvidenceIdIsKnownOnlyWithinItsTime() throws Exception {
        String service = service();
        NormalizedEvent e = line(service, "app_log", Severity.INFO, T0, "evidence", null);
        send(e);
        awaitLines(service, 1);

        assertThat(logs.existing(List.of(e.eventId(), UUID.randomUUID()), T0.minusSeconds(60), T0.plusSeconds(60)))
                .containsExactly(e.eventId());
        assertThat(logs.existing(List.of(e.eventId()), T0.plusSeconds(60), T0.plusSeconds(120))).isEmpty();
    }

    @Test
    void rejectsWhatItCannotServe() throws Exception {
        assertProblem("/api/v1/logs?from=" + T0 + "&to=" + T0.plus(Duration.ofHours(25)), "VALIDATION_FAILED");
        assertProblem("/api/v1/logs?q=ab", "VALIDATION_FAILED");
        assertProblem("/api/v1/logs?min_severity=loud", "VALIDATION_FAILED");
        assertProblem("/api/v1/logs/counts?by=message", "VALIDATION_FAILED");
        assertProblem("/api/v1/logs?cursor=nope", "INVALID_CURSOR");
    }

    private void assertProblem(String path, String code) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(OpsJson.mapper().readTree(response.body()).path("code").asString()).isEqualTo(code);
    }

    private JsonNode search(String service, String query) throws Exception {
        return getJson("/api/v1/logs?service=" + service + "&from=" + T0.minusSeconds(60) + "&to="
                + T0.plusSeconds(60) + query);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(i -> ids.add(i.path("event_id").asString()));
        return ids;
    }

    private void awaitLines(String service, long count) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT count(*) FROM log_events WHERE service = ?").param(service).query(Long.class).single() == count);
    }

    private static void send(NormalizedEvent e) throws Exception {
        PRODUCER.send(new ProducerRecord<>(Topics.LOGS_NORMALIZED, e.serviceOrHost(), OpsJson.toJson(e))).get();
    }

    private static NormalizedEvent line(String service, String eventType, Severity severity, Instant at,
            String message, String traceId) {
        return new NormalizedEvent(UUID.randomUUID(), "src", SourceType.APP_SERVICE, null, "vps", service, null,
                eventType, severity, at, Instant.now(), message,
                traceId == null ? Map.of() : Map.of("trace.id", traceId), List.of());
    }

    private static String service() {
        return "svc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
