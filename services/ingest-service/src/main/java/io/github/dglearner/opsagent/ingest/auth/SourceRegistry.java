package io.github.dglearner.opsagent.ingest.auth;

import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Resolves an API key to its log source. Backed by configuration until core-service owns log sources (J3). */
@Component
public class SourceRegistry {

    private final Map<String, String> sourceIdByKeyHash;

    public SourceRegistry(IngestProperties properties) {
        this.sourceIdByKeyHash = properties.sources().stream()
                .collect(Collectors.toUnmodifiableMap(s -> s.apiKeySha256().toLowerCase(), IngestProperties.Source::id));
    }

    public Optional<String> findSourceId(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sourceIdByKeyHash.get(ApiKeys.sha256Hex(apiKey)));
    }
}
