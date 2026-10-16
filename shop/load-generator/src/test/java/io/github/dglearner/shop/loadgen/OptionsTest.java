package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void defaultsDescribeAModestRunAgainstLocalServices() {
        Options options = Options.parse();

        assertThat(options.orderUrl()).isEqualTo("http://localhost:8091");
        assertThat(options.rate()).isEqualTo(5);
        assertThat(options.duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.verify()).isTrue();
        assertThat(options.seed()).isEmpty();
    }

    @Test
    void parsesEveryOption() {
        Options options = Options.parse("--order-url", "http://shop:9000/", "--rate", "12.5", "--duration", "2m",
                "--concurrency", "4", "--duplicate-rate", "0.5", "--seed", "42", "--settle-timeout", "750ms",
                "--no-verify", "--report", "runs/a.json");

        assertThat(options.orderUrl()).isEqualTo("http://shop:9000");
        assertThat(options.rate()).isEqualTo(12.5);
        assertThat(options.duration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(options.concurrency()).isEqualTo(4);
        assertThat(options.duplicateRate()).isEqualTo(0.5);
        assertThat(options.seed()).hasValue(42);
        assertThat(options.settleTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(options.verify()).isFalse();
        assertThat(options.report()).isEqualTo(Path.of("runs/a.json"));
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> Options.parse("--rate", "0")).hasMessageContaining("--rate must be positive");
        assertThatThrownBy(() -> Options.parse("--duplicate-rate", "1.5")).hasMessageContaining("between 0 and 1");
        assertThatThrownBy(() -> Options.parse("--duration", "soon")).hasMessageContaining("Not a duration");
        assertThatThrownBy(() -> Options.parse("--rate")).hasMessageContaining("Missing value");
        assertThatThrownBy(() -> Options.parse("--fast")).hasMessageContaining("Unknown option");
    }
}
