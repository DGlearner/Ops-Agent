package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/** Evaluates a rule's match conditions against an event and computes its grouping. */
public final class RuleMatcher {

    static final String MISSING = "unknown";

    public static Optional<RuleMatch> match(RuleDefinition rule, NormalizedEvent event) {
        if (!rule.enabled() || !matches(rule.match(), event)) {
            return Optional.empty();
        }
        Map<String, String> groupValues = new LinkedHashMap<>();
        for (String attribute : rule.groupBy()) {
            groupValues.put(attribute, attribute(event, attribute));
        }
        return Optional.of(new RuleMatch(rule.ruleId(), rule.version(), rule.window(), rule.threshold(),
                rule.severity(), rule.suppressFor(), rule.summaryTemplate(), fingerprint(rule.ruleId(), groupValues),
                groupValues, event.eventId(), event.timestamp(), event.service(), event.host()));
    }

    static boolean matches(RuleDefinition.Match match, NormalizedEvent event) {
        if (!match.eventTypes().isEmpty() && !match.eventTypes().contains(event.eventType())) {
            return false;
        }
        if (!match.sourceTypes().isEmpty() && !match.sourceTypes().contains(event.sourceType())) {
            return false;
        }
        if (match.minSeverity() != null && !event.severity().isAtLeast(match.minSeverity())) {
            return false;
        }
        for (var condition : match.fieldEquals().entrySet()) {
            Object value = event.fields().get(condition.getKey());
            if (value == null || !value.toString().equals(condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** {@code rule-id|host=app-01|fields.src_ip=198.51.100.240} */
    static String fingerprint(String ruleId, Map<String, String> groupValues) {
        StringJoiner joiner = new StringJoiner("|").add(ruleId);
        groupValues.forEach((k, v) -> joiner.add(k + "=" + v));
        return joiner.toString();
    }

    static String attribute(NormalizedEvent event, String attribute) {
        Object value = switch (attribute) {
            case "host" -> event.host();
            case "service" -> event.service();
            case "component" -> event.component();
            case "event_type" -> event.eventType();
            default -> attribute.startsWith("fields.") ? event.fields().get(attribute.substring(7)) : null;
        };
        return value == null ? MISSING : value.toString();
    }

    private RuleMatcher() {
    }
}
