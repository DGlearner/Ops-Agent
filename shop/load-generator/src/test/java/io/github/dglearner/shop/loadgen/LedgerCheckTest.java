package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerCheckTest {

    private static ShopClient.StockRow row(String sku, int total, int available, int reserved, int sold) {
        return new ShopClient.StockRow(sku, total, available, reserved, sold);
    }

    @Test
    void passesWhenSoldUnitsMatchPaidOrdersAndReservationsAreSettled() {
        var before = Map.of("A", row("A", 100, 90, 0, 10), "B", row("B", 20, 20, 0, 0));
        var after = Map.of("A", row("A", 100, 85, 0, 15), "B", row("B", 20, 17, 1, 2));

        LedgerCheck.Result result = LedgerCheck.check(before, after, Map.of("A", 5, "B", 2), Map.of("B", 1));

        assertThat(result.ok()).isTrue();
        assertThat(result.unitsSold()).isEqualTo(7);
        assertThat(result.unitsStillReserved()).isEqualTo(1);
    }

    @Test
    void reportsOversellingLeakedReservationsAndRestocks() {
        var before = Map.of("A", row("A", 100, 100, 0, 0), "B", row("B", 20, 20, 0, 0), "C", row("C", 5, 5, 0, 0));
        var after = Map.of("A", row("A", 100, 94, 0, 6), "B", row("B", 20, 18, 2, 0), "C", row("C", 8, 8, 0, 0));

        LedgerCheck.Result result = LedgerCheck.check(before, after, Map.of("A", 5), Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).containsExactly(
                "A: sold +6 but paid orders contain 5",
                "B: reserved +2 but unresolved orders hold 0",
                "C: total changed from 5 to 8 (restocked during the run?)");
    }
}
