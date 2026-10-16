package io.github.dglearner.shop.order.order;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Business counters; see docs/shop-design.md §8.3. */
@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** How a newly placed order ended up, e.g. {@code cancelled/out_of_stock}. */
    public void placed(Order order) {
        String reason = order.failureReason() == null ? "none" : order.failureReason().name().toLowerCase(Locale.ROOT);
        registry.counter("shop.orders", "status", order.status().name().toLowerCase(Locale.ROOT), "reason", reason)
                .increment();
    }

    public void replayed() {
        registry.counter("shop.idempotent.replays", "operation", "create_order").increment();
    }
}
