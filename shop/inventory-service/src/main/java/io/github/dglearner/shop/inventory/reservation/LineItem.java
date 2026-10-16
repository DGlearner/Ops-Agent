package io.github.dglearner.shop.inventory.reservation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LineItem(@NotBlank @Size(max = 32) String sku, @Min(1) @Max(100) int quantity) {
}
