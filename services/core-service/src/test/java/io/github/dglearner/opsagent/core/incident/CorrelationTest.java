package io.github.dglearner.opsagent.core.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.Severity;
import io.github.dglearner.opsagent.core.alerting.AlertStored;
import io.github.dglearner.opsagent.core.catalog.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationTest {

    private static final Topology SHOP = new Topology(Map.of(), Map.of(
            "order-service", List.of("inventory-service", "payment-service", "shop-mysql"),
            "inventory-service", List.of("shop-mysql"),
            "payment-service", List.of("shop-mysql")));
    private static final Duration GAP = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-17T09:58:00Z");

    /** Incidents built up alert by alert, the way the correlator does. */
    private final Map<UUID, Correlation.Candidate> incidents = new LinkedHashMap<>();

    @Test
    void theAlertsOfOneMysqlOutageMakeOneIncident() {
        UUID first = place(alert("order-service", null, T0));
        assertThat(place(alert("order-service", null, T0.plusSeconds(18)))).isEqualTo(first);
        assertThat(place(alert("payment-service", null, T0.plusSeconds(20)))).isEqualTo(first);
        assertThat(place(alert("order-service", null, T0.plusSeconds(22)))).isEqualTo(first);

        assertThat(incidents).hasSize(1);
        assertThat(Correlation.sharedDependencies(incidents.get(first).subjects(), SHOP)).containsExactly("shop-mysql");
    }

    @Test
    void independentPaymentAndInventoryFaultsStayTwoIncidents() {
        UUID payment = place(alert("order-service", "payment-service", T0));
        UUID inventory = place(alert("order-service", "inventory-service", T0.plusSeconds(5)));

        assertThat(inventory).isNotEqualTo(payment);
        assertThat(place(alert("order-service", "payment-service", T0.plusSeconds(30)))).isEqualTo(payment);
        assertThat(place(alert("order-service", "inventory-service", T0.plusSeconds(31)))).isEqualTo(inventory);
    }

    @Test
    void anAlertLongAfterTheLastOneOpensANewIncident() {
        UUID first = place(alert("order-service", null, T0));

        assertThat(place(alert("order-service", null, T0.plus(GAP).plusSeconds(1)))).isNotEqualTo(first);
    }

    @Test
    void theSubjectIsTheCalleeOfAFailingCallElseTheService() {
        assertThat(Correlation.subject(alert("order-service", "payment-service", T0))).isEqualTo("payment-service");
        assertThat(Correlation.subject(alert("order-service", null, T0))).isEqualTo("order-service");
        assertThat(Correlation.subject(alert("unknown", "unknown", T0))).isEqualTo("kozy");
    }

    @Test
    void servicesOutsideTheCatalogCorrelateOnlyWithThemselves() {
        UUID a = place(alert("billing", null, T0));

        assertThat(place(alert("billing", null, T0.plusSeconds(1)))).isEqualTo(a);
        assertThat(place(alert("search", null, T0.plusSeconds(2)))).isNotEqualTo(a);
    }

    @Test
    void sharedDependenciesNeedTwoSubjectsAndLeaveTheSubjectsOut() {
        assertThat(Correlation.sharedDependencies(List.of("payment-service"), SHOP)).isEmpty();
        assertThat(Correlation.sharedDependencies(List.of("order-service", "inventory-service"), SHOP))
                .containsExactly("shop-mysql");
    }

    private UUID place(AlertStored alert) {
        String subject = Correlation.subject(alert);
        Optional<UUID> chosen = Correlation.choose(subject, alert.createdAt(), incidents.values(), SHOP, GAP);
        if (chosen.isPresent()) {
            Correlation.Candidate c = incidents.get(chosen.get());
            Set<String> subjects = new HashSet<>(c.subjects());
            subjects.add(subject);
            Instant last = alert.createdAt().isAfter(c.lastAlertAt()) ? alert.createdAt() : c.lastAlertAt();
            incidents.put(c.incidentId(), new Correlation.Candidate(c.incidentId(), subjects, c.openedAt(), last));
            return c.incidentId();
        }
        UUID id = UUID.randomUUID();
        incidents.put(id, new Correlation.Candidate(id, Set.of(subject), alert.createdAt(), alert.createdAt()));
        return id;
    }

    private static AlertStored alert(String service, String dependency, Instant at) {
        Map<String, String> groupValues = new LinkedHashMap<>();
        groupValues.put("service", service);
        if (dependency != null) {
            groupValues.put("fields.dependency", dependency);
        }
        return new AlertStored(UUID.randomUUID(), "rule", Severity.MEDIUM, service, "kozy", groupValues, "s", at);
    }
}
