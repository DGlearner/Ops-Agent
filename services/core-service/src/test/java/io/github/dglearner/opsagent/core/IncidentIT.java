package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Alerts from Kafka grouped into incidents. Each test uses its own day, far from the others' alerts, since the
 * database is shared; incidents are looked up by their opening time.
 */
class IncidentIT extends CoreTestSupport {

    @Test
    void theAlertsOfAMysqlOutageMakeOneIncidentWithItsHistory() throws Exception {
        Instant t = Instant.parse("2027-01-01T10:00:00Z");
        List<Alert> outage = List.of(
                alert("shop-database-unavailable", "order-service", null, Severity.HIGH, t),
                alert("shop-recovery-failing", "payment-service", null, Severity.MEDIUM, t.plusSeconds(16)),
                alert("shop-recovery-failing", "order-service", null, Severity.MEDIUM, t.plusSeconds(17)),
                alert("shop-recovery-failing", "order-service", null, Severity.MEDIUM, t.plusSeconds(19)),
                alert("shop-recovery-failing", "order-service", null, Severity.MEDIUM, t.plusSeconds(21)));
        for (Alert alert : outage) {
            send(alert);
        }
        send(outage.get(2));

        JsonNode incident = awaitIncidents(t, 1, 5).getFirst();
        assertThat(incident.path("status").asString()).isEqualTo("open");
        assertThat(incident.path("severity").asString()).isEqualTo("high");
        assertThat(strings(incident.path("services"))).containsExactlyInAnyOrder("order-service", "payment-service");
        assertThat(strings(incident.path("shared_dependencies"))).containsExactly("shop-mysql");
        String id = incident.path("incident_id").asString();
        assertThat(getJson("/api/v1/incidents/" + id + "/alerts")).hasSize(5);
        JsonNode timeline = getJson("/api/v1/incidents/" + id + "/timeline");
        assertThat(timeline.findValuesAsString("type")).containsExactly("OPENED", "ALERT_ATTACHED", "ALERT_ATTACHED",
                "ALERT_ATTACHED", "ALERT_ATTACHED", "ALERT_ATTACHED");
    }

    @Test
    void independentPaymentAndInventoryFaultsAtTheSameTimeStayTwoIncidents() throws Exception {
        Instant t = Instant.parse("2027-02-01T10:00:00Z");
        send(alert("shop-dependency-call-failures", "order-service", "payment-service", Severity.MEDIUM, t));
        send(alert("shop-dependency-call-failures", "order-service", "inventory-service", Severity.MEDIUM,
                t.plusSeconds(3)));
        send(alert("shop-dependency-call-failures", "order-service", "payment-service", Severity.MEDIUM,
                t.plusSeconds(70)));

        List<JsonNode> incidents = awaitIncidents(t, 2, 3);
        assertThat(incidents).extracting(i -> strings(i.path("subjects")))
                .containsExactlyInAnyOrder(List.of("payment-service"), List.of("inventory-service"));
    }

    @Test
    void followsTheLifecycleAndANewAlertAfterResolutionOpensANewIncident() throws Exception {
        Instant t = Instant.parse("2027-03-01T10:00:00Z");
        send(alert("shop-unhandled-errors", "inventory-service", null, Severity.HIGH, t));
        String id = awaitIncidents(t, 1, 1).getFirst().path("incident_id").asString();
        String path = "/api/v1/incidents/" + id + "/transitions";

        assertThat(post(path, "{\"to_status\":\"acknowledged\",\"note\":\"on it\"}").statusCode()).isEqualTo(200);
        assertThat(post(path, "{\"to_status\":\"resolved\"}").statusCode()).isEqualTo(200);
        JsonNode resolved = getJson("/api/v1/incidents/" + id);
        assertThat(resolved.has("acknowledged_at")).isTrue();
        assertThat(resolved.has("resolved_at")).isTrue();

        send(alert("shop-unhandled-errors", "inventory-service", null, Severity.HIGH, t.plusSeconds(60)));
        assertThat(awaitIncidents(t, 2, 2)).extracting(i -> i.path("status").asString())
                .containsExactlyInAnyOrder("resolved", "open");

        assertThat(post(path, "{\"to_status\":\"closed\"}").statusCode()).isEqualTo(200);
        assertThat(post(path, "{\"to_status\":\"open\"}").body()).contains("ILLEGAL_TRANSITION");
        assertThat(post(path, "{\"to_status\":\"sleeping\"}").statusCode()).isEqualTo(400);
        assertThat(getJson("/api/v1/incidents/" + id + "/timeline").findValuesAsString("to_status"))
                .containsExactly("acknowledged", "resolved", "closed");
        assertThat(get("/api/v1/incidents/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }
}
