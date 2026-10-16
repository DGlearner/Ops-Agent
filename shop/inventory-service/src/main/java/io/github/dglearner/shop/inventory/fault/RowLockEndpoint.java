package io.github.dglearner.shop.inventory.fault;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/rowlock} on the management port:
 * {@code POST {"sku":"SKU-2001","duration":"20s"}} holds the row lock, {@code GET} lists held locks and
 * {@code DELETE} releases them early.
 */
@Component
@Endpoint(id = "rowlock")
public class RowLockEndpoint {

    private final RowLocks locks;

    public RowLockEndpoint(RowLocks locks) {
        this.locks = locks;
    }

    @ReadOperation
    public List<RowLocks.Held> held() {
        return locks.held();
    }

    @WriteOperation
    public RowLocks.Held lock(String sku, Duration duration) throws InterruptedException {
        try {
            return locks.lock(sku, duration);
        } catch (IllegalArgumentException e) {
            throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
        }
    }

    @DeleteOperation
    public Map<String, Integer> release() {
        return Map.of("released", locks.release());
    }
}
