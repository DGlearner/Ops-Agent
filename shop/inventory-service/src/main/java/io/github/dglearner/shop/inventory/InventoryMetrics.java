package io.github.dglearner.shop.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business counters; see docs/shop-design.md §8.3. */
@Component
public class InventoryMetrics {

    private final MeterRegistry registry;

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** First-time outcome of reserve, confirm or release, e.g. {@code reserve/insufficient_stock}. */
    public void operation(String operation, String result) {
        registry.counter("shop.inventory.operations", "operation", operation, "result", result).increment();
    }

    /** A repeated request answered from the stored result. */
    public void replay(String operation) {
        registry.counter("shop.idempotent.replays", "operation", operation).increment();
    }
}
