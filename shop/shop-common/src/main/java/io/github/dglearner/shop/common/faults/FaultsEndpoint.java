package io.github.dglearner.shop.common.faults;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * {@code /actuator/faults} on the management port (127.0.0.1 only):
 * <pre>
 * GET    /actuator/faults                                  list rules
 * POST   /actuator/faults {"path":"/api/v1/payments","phase":"after","delay":"4s","errors":0.3,"status":503,
 *                         "duration":"60s"}
 * DELETE /actuator/faults                                  remove all rules
 * </pre>
 * {@code phase} defaults to {@code before}, {@code delay} to none, {@code errors} to 0, {@code status} to 503 and
 * {@code duration} (how long the rule stays active before it expires) to 10 minutes; {@code method} optionally
 * restricts the rule to one HTTP method.
 */
@Endpoint(id = "faults")
public class FaultsEndpoint {

    private final FaultInjector injector;

    public FaultsEndpoint(FaultInjector injector) {
        this.injector = injector;
    }

    @ReadOperation
    public List<FaultRule> rules() {
        return injector.rules();
    }

    @WriteOperation
    public FaultRule add(String path, @Nullable String method, @Nullable String phase, @Nullable Duration delay,
            @Nullable Double errors, @Nullable Integer status, @Nullable Duration duration) {
        try {
            return injector.add(path, method,
                    phase == null ? FaultRule.Phase.BEFORE : FaultRule.Phase.valueOf(phase.toUpperCase(Locale.ROOT)),
                    delay == null ? Duration.ZERO : delay, errors == null ? 0 : errors, status == null ? 503 : status,
                    duration == null ? FaultInjector.DEFAULT_DURATION : duration);
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }

    @DeleteOperation
    public Map<String, Integer> clear() {
        return Map.of("removed", injector.clear());
    }
}
