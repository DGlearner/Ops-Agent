package io.github.dglearner.shop.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChargeRequest(
        @NotBlank @Size(max = 36) String orderId,
        @NotBlank @Size(max = 64) String userId,
        @NotNull @Positive Long amountCents,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 32) String paymentToken) {
}
