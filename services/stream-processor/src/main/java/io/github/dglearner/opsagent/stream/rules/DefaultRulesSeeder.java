package io.github.dglearner.opsagent.stream.rules;

import io.github.dglearner.opsagent.contracts.RuleDefinition;
import io.github.dglearner.opsagent.contracts.Topics;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the default rules to {@code rules.changelog} at startup when {@code ops.stream.seed-rules=true}. A
 * stop-gap for local development: from J3, core-service owns the rules and this stays disabled.
 */
@Component
@ConditionalOnBooleanProperty("ops.stream.seed-rules")
public class DefaultRulesSeeder {

    private static final Logger log = LoggerFactory.getLogger(DefaultRulesSeeder.class);

    private final KafkaTemplate<String, RuleDefinition> template;

    public DefaultRulesSeeder(KafkaTemplate<String, RuleDefinition> template) {
        this.template = template;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() throws Exception {
        List<RuleDefinition> rules = DefaultRules.load();
        CompletableFuture.allOf(rules.stream()
                        .map(rule -> template.send(Topics.RULES_CHANGELOG, rule.ruleId(), rule))
                        .toArray(CompletableFuture[]::new))
                .get(30, TimeUnit.SECONDS);
        log.info("Published {} default rules to {}", rules.size(), Topics.RULES_CHANGELOG);
    }
}
