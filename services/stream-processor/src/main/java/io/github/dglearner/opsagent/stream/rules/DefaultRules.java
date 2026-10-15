package io.github.dglearner.opsagent.stream.rules;

import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import tools.jackson.core.type.TypeReference;

/** The rule set in {@code rules/default-rules.json}. */
public final class DefaultRules {

    public static List<RuleDefinition> load() {
        try (InputStream in = DefaultRules.class.getResourceAsStream("/rules/default-rules.json")) {
            if (in == null) {
                throw new IllegalStateException("rules/default-rules.json is missing from the classpath");
            }
            return OpsJson.mapper().readValue(in, new TypeReference<List<RuleDefinition>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DefaultRules() {
    }
}
