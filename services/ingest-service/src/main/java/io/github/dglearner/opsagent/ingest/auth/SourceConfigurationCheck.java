package io.github.dglearner.opsagent.ingest.auth;

import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with a source configuration that cannot be right: no sources at all (every request would be
 * rejected), or the published development key outside the {@code local} profile. A hash of a public key keeps nothing
 * secret, so a shared environment that forgot to configure its own keys must not quietly accept it.
 */
@Component
public class SourceConfigurationCheck {

    static final String DEVELOPMENT_KEY_SHA256 = ApiKeys.sha256Hex("dev-ingest-key");

    public SourceConfigurationCheck(IngestProperties properties, Environment environment) {
        if (properties.sources().isEmpty()) {
            throw new IllegalStateException("No log sources configured (ops.ingest.sources): every request would be "
                    + "rejected. Configure at least one source, or run with the local profile for development.");
        }
        boolean development = properties.sources().stream()
                .anyMatch(s -> DEVELOPMENT_KEY_SHA256.equalsIgnoreCase(s.apiKeySha256()));
        if (development && !environment.matchesProfiles("local")) {
            throw new IllegalStateException("The development API key (dev-ingest-key) is configured outside the "
                    + "local profile; configure a key of your own for this environment.");
        }
    }
}
