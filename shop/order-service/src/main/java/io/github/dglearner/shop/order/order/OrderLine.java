package io.github.dglearner.shop.order.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderLine(@NotBlank @Size(max = 32) String sku, @Min(1) @Max(100) int quantity) {
}
