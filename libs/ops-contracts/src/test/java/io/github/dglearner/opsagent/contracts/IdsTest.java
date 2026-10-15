package io.github.dglearner.opsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void newIdIsVersion7AndOrderedByCreationTime() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(first.toString()).isLessThan(second.toString());
        assertThat(first.getMostSignificantBits() >>> 16).isLessThan(second.getMostSignificantBits() >>> 16);
    }

    @Test
    void nameBasedIdIsStableVersion5() {
        UUID a = Ids.nameBased("rule|host=a|2026-01-01T00:00:00Z");

        assertThat(a).isEqualTo(Ids.nameBased("rule|host=a|2026-01-01T00:00:00Z"));
        assertThat(a).isNotEqualTo(Ids.nameBased("rule|host=b|2026-01-01T00:00:00Z"));
        assertThat(a.version()).isEqualTo(5);
        assertThat(a.variant()).isEqualTo(2);
    }
}
