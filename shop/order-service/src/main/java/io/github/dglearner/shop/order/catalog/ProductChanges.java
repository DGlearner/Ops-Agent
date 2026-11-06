package io.github.dglearner.shop.order.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** What to change in a product; fields left out stay as they are. */
public record ProductChanges(@Size(min = 1, max = 128) String name, @Size(max = 512) String description,
        @Min(1) @Max(1_000_000_000) Long priceCents, Boolean active) {

    boolean isEmpty() {
        return name == null && description == null && priceCents == null && active == null;
    }
}
