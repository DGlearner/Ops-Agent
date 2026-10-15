package io.github.dglearner.opsagent.stream.parse;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.stream.Datasets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Every line of the sample dataset must parse, and the incident-relevant lines must be classified correctly. */
class DatasetParsingTest {

    private final ParserRegistry registry = ParserRegistry.withDefaultParsers();
    private final List<RawLogRecord> dataset = Datasets.load("aiops_mixed_incident_small.jsonl");

    @Test
    void parsesEveryLine() {
        List<ParseOutcome> outcomes = dataset.stream().map(registry::parse).toList();

        assertThat(outcomes).hasSize(100);
        assertThat(outcomes).filteredOn(o -> o instanceof ParseOutcome.Unparsed).isEmpty();
    }

    @Test
    void classifiesIncidentRelevantEvents() {
        Map<String, Long> counts = events().stream()
                .collect(Collectors.groupingBy(NormalizedEvent::eventType, Collectors.counting()));

        assertThat(counts).containsEntry("ssh_invalid_user", 4L)
                .containsEntry("ssh_login_failed", 3L)
                .containsEntry("ssh_login_success", 2L)
                .containsEntry("mysql_connection_refused", 4L)
                .containsEntry("mysql_too_many_connections", 1L)
                .containsEntry("mysql_slow_query", 2L)
                .containsEntry("app_db_connection_error", 5L)
                .containsEntry("http_server_error", 8L)
                .containsEntry("container_oom_killed", 2L)
                .containsEntry("container_restarted", 3L)
                .containsEntry("container_unhealthy", 1L)
                .containsEntry("disk_usage", 5L);
    }

    @Test
    void usesTheTimeFromTheLogLine() {
        assertThat(events()).allSatisfy(e -> {
            assertThat(e.timestamp()).isAfterOrEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(e.timestamp()).isBefore(Datasets.RECEIVED_AT);
        });
    }

    @Test
    void extractsFieldsUsedByDefaultRules() {
        Map<String, NormalizedEvent> byType = events().stream()
                .collect(Collectors.toMap(NormalizedEvent::eventType, Function.identity(), (a, b) -> a));

        assertThat(byType.get("ssh_invalid_user").fields()).containsEntry("src_ip", "198.51.100.240");
        assertThat(byType.get("app_db_connection_error").fields()).containsEntry("db_host", "db-01:3306");
        assertThat(byType.get("container_oom_killed").fields()).containsEntry("container", "api-gateway.1");
        assertThat(byType.get("http_server_error").fields().get("status")).asString().startsWith("5");
    }

    private List<NormalizedEvent> events() {
        return dataset.stream().map(registry::parse)
                .map(o -> ((ParseOutcome.Parsed) o).event())
                .toList();
    }
}
