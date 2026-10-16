package io.github.dglearner.shop.order.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code payment_token} defaults to {@code tok_ok}; see the payment service for sandbox tokens. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_.-]+") String userId,
        @NotEmpty @Size(max = 10) List<@Valid @NotNull OrderLine> items,
        @Size(max = 32) @Pattern(regexp = "tok_[a-z_]+") String paymentToken) {

    public static final String DEFAULT_PAYMENT_TOKEN = "tok_ok";

    public String paymentTokenOrDefault() {
        return paymentToken == null ? DEFAULT_PAYMENT_TOKEN : paymentToken;
    }
}
