package io.github.dglearner.opsagent.stream.detect;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds alerts from window aggregates. */
public final class AlertFactory {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([\\w.]+)}");

    /**
     * The alert id is derived from the fingerprint and the start of the window that first crossed the threshold, so
     * reprocessing the same input yields the same id and consumers can upsert.
     */
    public static Alert create(String fingerprint, Instant windowStart, Instant windowEnd, WindowAggregate agg,
            Instant now) {
        RuleMatch rule = agg.latest();
        return new Alert(
                Ids.nameBased(fingerprint + "|" + windowStart),
                rule.ruleId(),
                rule.ruleVersion(),
                fingerprint,
                rule.severity(),
                rule.groupKey(),
                rule.service(),
                rule.host(),
                rule.groupValues(),
                windowStart,
                windowEnd,
                agg.count(),
                rule.threshold(),
                agg.firstEventAt(),
                agg.lastEventAt(),
                agg.sampleEventIds(),
                summary(rule, agg.count()),
                now);
    }

    /** Fills {@code {count} {threshold} {window} {rule} {host} {service}} and any group-by attribute. */
    static String summary(RuleMatch rule, long count) {
        Map<String, String> values = new HashMap<>(rule.groupValues());
        values.put("count", Long.toString(count));
        values.put("threshold", Integer.toString(rule.threshold()));
        values.put("window", humanizeWindow(rule.window()));
        values.put("rule", rule.ruleId());
        values.putIfAbsent("host", rule.host());
        values.putIfAbsent("service", rule.service() == null ? RuleMatcher.MISSING : rule.service());
        String template = rule.summaryTemplate() != null
                ? rule.summaryTemplate()
                : "{rule}: {count} events within {window} (threshold {threshold})";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), m.group())));
        }
        return m.appendTail(out).toString();
    }

    /** {@code PT5M} as {@code 5m}; also names the per-window sub-topologies. */
    public static String humanizeWindow(Duration duration) {
        return duration.toSecondsPart() == 0 && duration.toMinutes() > 0 ? duration.toMinutes() + "m"
                : duration.toSeconds() + "s";
    }

    private AlertFactory() {
    }
}
