package io.github.dglearner.shop.inventory.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReserveRequest(@NotBlank @Size(max = 36) String orderId,
        @NotEmpty @Size(max = 10) List<@Valid @NotNull LineItem> items) {
}
