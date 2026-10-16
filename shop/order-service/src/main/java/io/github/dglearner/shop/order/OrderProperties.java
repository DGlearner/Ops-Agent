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
        @Valid @NotNull Downstream payment) {

    /** Where a dependency lives and how long to wait for it: connecting, then for the response. */
    public record Downstream(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }
}
