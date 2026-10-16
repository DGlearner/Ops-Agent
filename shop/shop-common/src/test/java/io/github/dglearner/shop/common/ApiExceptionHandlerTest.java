package io.github.dglearner.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTest {

    @Test
    void reportsFieldPathsInSnakeCase() {
        assertThat(ApiExceptionHandler.snakeCase("skuCode")).isEqualTo("sku_code");
        assertThat(ApiExceptionHandler.snakeCase("items[0].unitPriceCents")).isEqualTo("items[0].unit_price_cents");
        assertThat(ApiExceptionHandler.snakeCase("quantity")).isEqualTo("quantity");
    }
}
