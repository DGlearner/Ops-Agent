package io.github.dglearner.shop.order.switches;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Degradation switches, an operator's tool rather than a fault (docs/agent-design.md §5.4, §13.3): with one on, the
 * order service does not call that dependency at all and cancels the order at once, instead of waiting for it to
 * time out or fail. Only the calls that start something (reserve, charge) are skipped; releasing, confirming and
 * voiding still go out, so the books stay balanced. A switch turns itself off when its time is up. Changes are
 * logged under {@code shop.switches}, which is collected: turning one on is part of handling an incident.
 */
@Component
public class Switches {

    public static final String PAYMENT_FAST_FAIL = "payment.fast-fail";
    public static final String INVENTORY_FAST_FAIL = "inventory.fast-fail";
    public static final Set<String> NAMES = Set.of(PAYMENT_FAST_FAIL, INVENTORY_FAST_FAIL);
    static final Duration MAX = Duration.ofHours(1);

    public record State(String name, boolean on, Instant until) {
    }

    private static final Logger log = LoggerFactory.getLogger("shop.switches");

    private final Map<String, Instant> on = new ConcurrentHashMap<>();
    private final Clock clock;

    public Switches() {
        this(Clock.systemUTC());
    }

    Switches(Clock clock) {
        this.clock = clock;
    }

    public boolean isOn(String name) {
        Instant until = on.get(name);
        if (until == null) {
            return false;
        }
        if (!clock.instant().isBefore(until)) {
            if (on.remove(name, until)) {
                log.atInfo().addKeyValue("action", "switch_expired").addKeyValue("switch", name)
                        .log("Switch {} turned itself off", name);
            }
            return false;
        }
        return true;
    }

    public State set(String name, boolean turnOn, Duration duration) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("No switch " + name + "; the switches are " + NAMES);
        }
        if (turnOn) {
            if (duration == null || duration.isNegative() || duration.isZero() || duration.compareTo(MAX) > 0) {
                throw new IllegalArgumentException("A switch is turned on for 1 second to 1 hour");
            }
            Instant until = clock.instant().plus(duration);
            on.put(name, until);
            log.atWarn().addKeyValue("action", "switch_on").addKeyValue("switch", name)
                    .addKeyValue("duration_s", duration.toSeconds())
                    .log("Switch {} turned on for {} s", name, duration.toSeconds());
        } else if (on.remove(name) != null) {
            log.atInfo().addKeyValue("action", "switch_off").addKeyValue("switch", name)
                    .log("Switch {} turned off", name);
        }
        return state(name);
    }

    public List<State> all() {
        return NAMES.stream().sorted().map(this::state).toList();
    }

    private State state(String name) {
        boolean current = isOn(name);
        return new State(name, current, current ? on.get(name) : null);
    }
}
