package io.github.dglearner.opsagent.ingest.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

class SourceConfigurationCheckTest {

    private static final IngestProperties.Source DEV =
            new IngestProperties.Source("dev", SourceConfigurationCheck.DEVELOPMENT_KEY_SHA256);
    private static final IngestProperties.Source OWN = new IngestProperties.Source("web", ApiKeys.sha256Hex("own"));

    @Test
    void developmentKeyIsAcceptedInTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setDefaultProfiles("local");

        assertThatCode(() -> new SourceConfigurationCheck(properties(DEV), local)).doesNotThrowAnyException();
    }

    @Test
    void developmentKeyIsRefusedInAnyOtherProfile() {
        MockEnvironment shared = new MockEnvironment();
        shared.setActiveProfiles("shared");

        assertThatThrownBy(() -> new SourceConfigurationCheck(properties(OWN, DEV), shared))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev-ingest-key");
        assertThatCode(() -> new SourceConfigurationCheck(properties(OWN), shared)).doesNotThrowAnyException();
    }

    @Test
    void noSourcesIsRefused() {
        assertThatThrownBy(() -> new SourceConfigurationCheck(properties(), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No log sources");
    }

    private static IngestProperties properties(IngestProperties.Source... sources) {
        return new IngestProperties(DataSize.ofKilobytes(4), Duration.ofSeconds(1), List.of(sources),
                new IngestProperties.RateLimit(true, 100, 10));
    }
}
