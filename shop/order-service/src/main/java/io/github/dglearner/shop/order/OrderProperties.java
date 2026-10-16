package io.github.dglearner.shop.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("shop.order")
public record OrderProperties(@NotBlank String currency, @Valid @NotNull Downstream inventory,
        @Valid @NotNull Downstream payment, @Valid @NotNull Recovery recovery) {

    /** Where a dependency lives and how long to wait for it: connecting, then for the response. */
    public record Downstream(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    /**
     * Background recovery (docs/shop-design.md §7.4–7.5): how often it runs, how long an order may sit in
     * PAYMENT_UNKNOWN before its payment is reconciled, how long CREATED or STOCK_RESERVED may last before the order
     * counts as abandoned by a crashed request, and the longest wait between inventory command retries.
     */
    public record Recovery(boolean enabled, @NotNull Duration interval, @NotNull Duration reconcileAfter,
            @NotNull Duration abandonAfter, @NotNull Duration maxBackoff) {
    }
}
