package io.github.dglearner.opsagent.core.agentops;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dglearner.opsagent.contracts.AgentResult.Evidence;
import io.github.dglearner.opsagent.contracts.AgentResult.Hypothesis;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceCheckTest {

    private static final UUID INCIDENT = UUID.fromString("0199f000-0000-7000-8000-000000000001");
    private static final String ALERT = "0199f000-0000-7000-8000-0000000000a1";
    private static final String EVENT = "0199f000-0000-7000-8000-0000000000e1";
    private static final EvidenceCheck.Known KNOWN = new EvidenceCheck.Known(INCIDENT, Set.of(ALERT), Set.of(EVENT),
            Set.of("order-service", "shop-mysql"));

    @Test
    void keepsWhatTheIncidentHasAndRejectsTheRest() {
        Evidence alert = new Evidence("alert", ALERT.toUpperCase(), "database unavailable");
        Evidence event = new Evidence("event", EVENT, null);
        Evidence topology = new Evidence("topology", "shop-mysql", "everything depends on it");
        Evidence incident = new Evidence("incident", INCIDENT.toString(), null);
        Evidence madeUp = new Evidence("alert", UUID.randomUUID().toString(), "never read");
        Evidence unknownService = new Evidence("topology", "redis", null);
        Evidence unknownKind = new Evidence("metric", "cpu", null);

        EvidenceCheck.Checked checked = EvidenceCheck.check(List.of(new Hypothesis("shop-mysql", "MySQL is down",
                "high", List.of(alert, event, topology, incident, madeUp, unknownService, unknownKind))), KNOWN);

        assertThat(checked.hypotheses().getFirst().evidence()).containsExactly(alert, event, topology, incident);
        assertThat(checked.rejected()).containsExactly(madeUp, unknownService, unknownKind);
        assertThat(checked.unsupported()).isFalse();
    }

    @Test
    void aHypothesisLeftWithoutEvidenceMakesTheAnalysisUnsupported() {
        EvidenceCheck.Checked checked = EvidenceCheck.check(List.of(
                new Hypothesis("shop-mysql", "MySQL is down", "high", List.of(new Evidence("alert", ALERT, null))),
                new Hypothesis("payment-service", "a guess", "low", List.of(new Evidence("alert", "x", null)))),
                KNOWN);

        assertThat(checked.hypotheses()).hasSize(2);
        assertThat(checked.hypotheses().get(1).evidence()).isEmpty();
        assertThat(checked.unsupported()).isTrue();
    }

    @Test
    void keepsAtMostFiveHypotheses() {
        Hypothesis h = new Hypothesis("shop-mysql", "c", "low", List.of(new Evidence("alert", ALERT, null)));
        assertThat(EvidenceCheck.check(List.of(h, h, h, h, h, h, h), KNOWN).hypotheses()).hasSize(5);
    }
}
