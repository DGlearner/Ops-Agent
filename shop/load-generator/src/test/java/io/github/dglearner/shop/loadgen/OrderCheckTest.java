package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

class OrderCheckTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<OrderMix.Line> LINES = List.of(new OrderMix.Line("SKU-1", 2), new OrderMix.Line("SKU-2", 1));

    @Test
    void paidOrderWasChargedAndItsExactItemsConfirmed() {
        OrderCheck.Result result = OrderCheck.check(order("PAID", "PAID"), found("SUCCEEDED"),
                reservation("CONFIRMED", "[{\"sku\":\"SKU-2\",\"quantity\":1},{\"sku\":\"SKU-1\",\"quantity\":2}]"));

        assertThat(result.problems()).isEmpty();
        assertThat(result.reservation()).isEqualTo("CONFIRMED");
    }

    @Test
    void paidOrderWithOtherItemsOrAnUnconfirmedReservationIsAProblem() {
        assertThat(OrderCheck.check(order("PAID", "PAID"), found("SUCCEEDED"),
                reservation("CONFIRMED", "[{\"sku\":\"SKU-1\",\"quantity\":1},{\"sku\":\"SKU-2\",\"quantity\":1}]"))
                .problems()).singleElement().asString().contains("reservation items");
        assertThat(OrderCheck.check(order("PAID", "PAID"), found("SUCCEEDED"), reservation("RELEASED", "[]"))
                .problems()).singleElement().asString().contains("reservation RELEASED");
    }

    @Test
    void cancelledOrderWasNotChargedAndHoldsNoStock() {
        OrderCheck.Order declined = order("CANCELLED", "CANCELLED/PAYMENT_DECLINED");
        OrderCheck.Order outOfStock = order("CANCELLED", "CANCELLED/OUT_OF_STOCK");

        assertThat(OrderCheck.check(declined, found("DECLINED"), reservation("RELEASED", "[]")).problems()).isEmpty();
        assertThat(OrderCheck.check(outOfStock, missing(), missing()).problems()).isEmpty();
        assertThat(OrderCheck.check(declined, found("DECLINED"), reservation("CONFIRMED", "[]")).problems())
                .singleElement().asString().contains("reservation CONFIRMED");
        assertThat(OrderCheck.check(declined, found("DECLINED"), reservation("RESERVED", "[]")).problems())
                .singleElement().asString().contains("reservation RESERVED");
        assertThat(OrderCheck.check(declined, found("SUCCEEDED"), missing()).problems())
                .singleElement().asString().contains("payment SUCCEEDED");
    }

    @Test
    void lookupErrorsAreProblems() {
        ShopClient.Response error = new ShopClient.Response(503, MissingNode.getInstance(), "", 0, null);

        assertThat(OrderCheck.check(order("PAID", "PAID"), error, error).problems()).hasSize(2);
    }

    private static OrderCheck.Order order(String status, String outcome) {
        return new OrderCheck.Order("o-1", status, outcome, LINES);
    }

    private static ShopClient.Response found(String status) {
        return new ShopClient.Response(200, JSON.readTree("{\"status\":\"" + status + "\"}"), "", 0, null);
    }

    private static ShopClient.Response reservation(String status, String items) {
        return new ShopClient.Response(200, JSON.readTree("{\"status\":\"" + status + "\",\"items\":" + items + "}"),
                "", 0, null);
    }

    private static ShopClient.Response missing() {
        return new ShopClient.Response(404, MissingNode.getInstance(), "", 0, null);
    }
}
