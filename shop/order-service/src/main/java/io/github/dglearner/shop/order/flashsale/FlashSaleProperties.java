package io.github.dglearner.shop.order.flashsale;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Taking accepted flash sale orders from the queue (docs/shop-design.md §16.3): whether this instance consumes at all,
 * how many consumer threads (so at most that many orders are placed at once), how many messages a read takes, and how
 * long a delivered message may stay unacknowledged before another consumer takes it over.
 */
@Validated
@ConfigurationProperties("shop.flash-sale")
public record FlashSaleProperties(@DefaultValue("true") boolean consume, @Min(1) @DefaultValue("4") int consumers,
        @Min(1) @DefaultValue("10") int batch, @NotNull @DefaultValue("30s") Duration claimAfter) {
}
