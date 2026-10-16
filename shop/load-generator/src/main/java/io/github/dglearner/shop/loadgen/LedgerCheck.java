package io.github.dglearner.shop.loadgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares stock before and after a run with what the run's orders say (docs/shop-design.md §7.6): every unit sold
 * belongs to a paid order, and reserved stock is back where it started, apart from orders still unresolved.
 */
final class LedgerCheck {

    record Result(List<String> problems, int unitsSold, int unitsStillReserved) {

        boolean ok() {
            return problems.isEmpty();
        }
    }

    static Result check(Map<String, ShopClient.StockRow> before, Map<String, ShopClient.StockRow> after,
            Map<String, Integer> paidUnits, Map<String, Integer> heldUnits) {
        List<String> problems = new ArrayList<>();
        int sold = 0;
        int reserved = 0;
        TreeSet<String> skus = new TreeSet<>(after.keySet());
        skus.addAll(before.keySet());
        for (String sku : skus) {
            ShopClient.StockRow b = before.get(sku);
            ShopClient.StockRow a = after.get(sku);
            if (b == null || a == null) {
                problems.add(sku + ": appeared or disappeared during the run");
                continue;
            }
            if (a.available() + a.reserved() + a.sold() != a.total()) {
                problems.add(sku + ": available + reserved + sold != total");
            }
            if (a.total() != b.total()) {
                problems.add(sku + ": total changed from " + b.total() + " to " + a.total()
                        + " (restocked during the run?)");
            }
            int soldDelta = a.sold() - b.sold();
            int expectedSold = paidUnits.getOrDefault(sku, 0);
            if (soldDelta != expectedSold) {
                problems.add(sku + ": sold +" + soldDelta + " but paid orders contain " + expectedSold);
            }
            int reservedDelta = a.reserved() - b.reserved();
            int expectedReserved = heldUnits.getOrDefault(sku, 0);
            if (reservedDelta != expectedReserved) {
                problems.add(sku + ": reserved " + signed(reservedDelta) + " but unresolved orders hold "
                        + expectedReserved);
            }
            sold += soldDelta;
            reserved += reservedDelta;
        }
        return new Result(List.copyOf(problems), sold, reserved);
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : Integer.toString(n);
    }

    private LedgerCheck() {
    }
}
