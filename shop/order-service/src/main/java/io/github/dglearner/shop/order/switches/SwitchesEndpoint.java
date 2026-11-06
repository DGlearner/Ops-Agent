package io.github.dglearner.shop.order.switches;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/switches} on the management port: {@code GET} lists the switches,
 * {@code POST {"name":"payment.fast-fail","on":true,"minutes":10}} turns one on or off. Separate from
 * {@code /actuator/faults}: a switch changes how the service behaves, it knows nothing of injected faults.
 */
@Component
@Endpoint(id = "switches")
public class SwitchesEndpoint {

    private final Switches switches;

    public SwitchesEndpoint(Switches switches) {
        this.switches = switches;
    }

    @ReadOperation
    public List<Switches.State> list() {
        return switches.all();
    }

    @WriteOperation
    public Switches.State set(String name, boolean on, @Nullable Integer minutes) {
        try {
            return switches.set(name, on, minutes == null ? Duration.ofMinutes(10) : Duration.ofMinutes(minutes));
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }
}
