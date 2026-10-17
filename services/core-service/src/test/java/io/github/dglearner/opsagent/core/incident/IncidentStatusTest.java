package io.github.dglearner.opsagent.core.incident;

import static io.github.dglearner.opsagent.core.incident.IncidentStatus.ACKNOWLEDGED;
import static io.github.dglearner.opsagent.core.incident.IncidentStatus.CLOSED;
import static io.github.dglearner.opsagent.core.incident.IncidentStatus.INVESTIGATING;
import static io.github.dglearner.opsagent.core.incident.IncidentStatus.MITIGATED;
import static io.github.dglearner.opsagent.core.incident.IncidentStatus.OPEN;
import static io.github.dglearner.opsagent.core.incident.IncidentStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IncidentStatusTest {

    @Test
    void movesForwardAndResolvesFromAnyActiveState() {
        assertThat(OPEN.canMoveTo(ACKNOWLEDGED)).isTrue();
        assertThat(ACKNOWLEDGED.canMoveTo(INVESTIGATING)).isTrue();
        assertThat(INVESTIGATING.canMoveTo(MITIGATED)).isTrue();
        for (IncidentStatus active : IncidentStatus.ACTIVE) {
            assertThat(active.canMoveTo(RESOLVED)).as(active.name()).isTrue();
        }
        assertThat(RESOLVED.canMoveTo(CLOSED)).isTrue();
    }

    @Test
    void reopensOnlyUntilClosedAndNeverGoesBackOtherwise() {
        assertThat(RESOLVED.canMoveTo(OPEN)).isTrue();
        assertThat(CLOSED.canMoveTo(OPEN)).isFalse();
        assertThat(INVESTIGATING.canMoveTo(ACKNOWLEDGED)).isFalse();
        assertThat(OPEN.canMoveTo(CLOSED)).isFalse();
        assertThat(MITIGATED.canMoveTo(INVESTIGATING)).as("a mitigation that did not hold").isTrue();
    }
}
