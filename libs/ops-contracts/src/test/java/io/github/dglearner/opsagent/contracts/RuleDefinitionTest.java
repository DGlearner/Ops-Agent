package io.github.dglearner.opsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleDefinitionTest {

    @Test
    void rejectsUnsupportedWindowAndThreshold() {
        assertThatThrownBy(() -> rule(Duration.ofMinutes(2), 1)).hasMessageContaining("window");
        assertThatThrownBy(() -> rule(Duration.ofMinutes(1), 0)).hasMessageContaining("threshold");
    }

    @Test
    void defaultsSuppressionToWindowAndMatchToAny() {
        RuleDefinition rule = rule(Duration.ofMinutes(15), 3);

        assertThat(rule.suppressFor()).isEqualTo(Duration.ofMinutes(15));
        assertThat(rule.match()).isEqualTo(RuleDefinition.Match.ANY);
    }

    private static RuleDefinition rule(Duration window, int threshold) {
        return new RuleDefinition("r", 1, true, "r", null, List.of("host"), window, threshold, Severity.LOW, null,
                null);
    }
}
