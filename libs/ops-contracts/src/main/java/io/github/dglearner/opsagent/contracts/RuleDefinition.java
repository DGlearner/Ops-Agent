package io.github.dglearner.opsagent.contracts;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A detection rule. Topic {@link Topics#RULES_CHANGELOG} (compacted), key = {@code rule_id}; a null value deletes
 * the rule.
 *
 * @param groupBy     event attributes that form the counting key: {@code host}, {@code service}, {@code component},
 *                    {@code event_type} or {@code fields.<name>}
 * @param window      one of {@link #SUPPORTED_WINDOWS}
 * @param suppressFor after an alert, further alerts with the same fingerprint are dropped for this long
 */
public record RuleDefinition(
        String ruleId,
        int version,
        boolean enabled,
        String name,
        Match match,
        List<String> groupBy,
        Duration window,
        int threshold,
        Severity severity,
        Duration suppressFor,
        String summaryTemplate) {

    /** Window sizes are fixed when the topology is built, so rules pick one of these tiers. */
    public static final Set<Duration> SUPPORTED_WINDOWS =
            Set.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));

    public RuleDefinition {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(severity, "severity");
        match = match == null ? Match.ANY : match;
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        suppressFor = suppressFor == null ? window : suppressFor;
        if (!SUPPORTED_WINDOWS.contains(window)) {
            throw new IllegalArgumentException("window must be one of PT1M, PT5M, PT15M but was " + window);
        }
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be at least 1 but was " + threshold);
        }
    }

    /**
     * Conditions that are all required to hold. A null or empty condition matches everything.
     *
     * @param fieldEquals exact string comparison against {@code NormalizedEvent.fields}
     */
    public record Match(
            List<String> eventTypes,
            List<SourceType> sourceTypes,
            Severity minSeverity,
            Map<String, String> fieldEquals) {

        public static final Match ANY = new Match(null, null, null, null);

        public Match {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            sourceTypes = sourceTypes == null ? List.of() : List.copyOf(sourceTypes);
            fieldEquals = fieldEquals == null ? Map.of() : Map.copyOf(fieldEquals);
        }
    }
}
