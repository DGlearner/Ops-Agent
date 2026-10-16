package io.github.dglearner.shop.common.faults;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Active fault rules of this service, changed at runtime through the {@code faults} actuator endpoint.
 *
 * <p>Changes are logged under the {@code shop.faults} logger only, and no metrics are recorded: the fault timeline is
 * the ground truth for evaluating Ops-Agent and must not leak into what it observes. Its collector should drop the
 * {@code shop.faults} logger.
 */
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger("shop.faults");

    private static final Duration MAX_DELAY = Duration.ofMinutes(2);

    private record Active(FaultRule rule, PathPattern pattern) {
    }

    private final List<Active> rules = new CopyOnWriteArrayList<>();

    public FaultRule add(String path, String method, FaultRule.Phase phase, Duration delay, double errorRate,
            int errorStatus) {
        if (delay.isNegative() || delay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("delay must be between 0 and " + MAX_DELAY.toSeconds() + "s");
        }
        if (errorRate < 0 || errorRate > 1) {
            throw new IllegalArgumentException("errors must be a rate between 0 and 1");
        }
        if (errorStatus < 500 || errorStatus > 599) {
            throw new IllegalArgumentException("status must be a 5xx code");
        }
        PathPattern pattern = PathPatternParser.defaultInstance.parse(path);
        FaultRule rule = new FaultRule(UUID.randomUUID().toString().substring(0, 8), path,
                method == null ? null : method.toUpperCase(Locale.ROOT), phase, delay, errorRate, errorStatus,
                Instant.now());
        rules.add(new Active(rule, pattern));
        log.atInfo().addKeyValue("fault_id", rule.id()).addKeyValue("path", path).addKeyValue("phase", phase)
                .addKeyValue("delay_ms", delay.toMillis()).addKeyValue("error_rate", errorRate)
                .log("Fault rule added");
        return rule;
    }

    public List<FaultRule> rules() {
        return rules.stream().map(Active::rule).toList();
    }

    public int clear() {
        int removed = rules.size();
        rules.clear();
        log.atInfo().addKeyValue("removed", removed).log("Fault rules cleared");
        return removed;
    }

    /** The first rule matching this request, if any. */
    public Optional<FaultRule> match(String method, String path) {
        if (rules.isEmpty()) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        return rules.stream()
                .filter(a -> a.rule().method() == null || a.rule().method().equals(method))
                .filter(a -> a.pattern().matches(container))
                .map(Active::rule)
                .findFirst();
    }

    /** Whether this request is one of the share that should fail. */
    static boolean fails(FaultRule rule) {
        return rule.errorRate() > 0 && ThreadLocalRandom.current().nextDouble() < rule.errorRate();
    }
}
