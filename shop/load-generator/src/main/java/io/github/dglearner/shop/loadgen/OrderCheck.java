package io.github.dglearner.shop.loadgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks one finished order against what payment and inventory recorded for it (docs/shop-design.md §7.6): a paid
 * order was charged and its reservation confirmed with exactly its items; a cancelled order was not charged and its
 * stock, if any was reserved, was released. Per order, so two errors cannot cancel out the way they can in the
 * per-SKU ledger.
 */
final class OrderCheck {

    /** An order as the run saw it: {@code status} is PAID or CANCELLED, {@code outcome} adds the failure reason. */
    record Order(String orderId, String status, String outcome, List<OrderMix.Line> lines) {
    }

    /** The payment and reservation states found, e.g. SUCCEEDED and CONFIRMED, or NONE when there is no record. */
    record Result(String payment, String reservation, List<String> paymentProblems, List<String> stockProblems) {

        List<String> problems() {
            List<String> all = new ArrayList<>(paymentProblems);
            all.addAll(stockProblems);
            return all;
        }
    }

    static Result check(Order order, ShopClient.Response payment, ShopClient.Response reservation) {
        String paymentStatus = state(payment);
        String reservationStatus = state(reservation);
        List<String> paymentProblems = new ArrayList<>();
        List<String> stockProblems = new ArrayList<>();
        boolean paid = "PAID".equals(order.status());

        if (paymentStatus.equals("ERROR") || paid != paymentStatus.equals("SUCCEEDED")) {
            paymentProblems.add(order.orderId() + ": order " + order.outcome() + " but payment " + paymentStatus);
        }
        if (paid) {
            if (!reservationStatus.equals("CONFIRMED")) {
                stockProblems.add(order.orderId() + ": order PAID but reservation " + reservationStatus);
            } else if (!items(reservation).equals(items(order.lines()))) {
                stockProblems.add(order.orderId() + ": reservation items " + items(reservation) + " but order items "
                        + items(order.lines()));
            }
        } else if (!reservationStatus.equals("RELEASED") && !reservationStatus.equals("NONE")) {
            stockProblems.add(order.orderId() + ": order " + order.outcome() + " but reservation " + reservationStatus);
        }
        return new Result(paymentStatus, reservationStatus, paymentProblems, stockProblems);
    }

    private static String state(ShopClient.Response response) {
        return switch (response.status()) {
            case 200 -> response.body().path("status").asString();
            case 404 -> "NONE";
            default -> "ERROR";
        };
    }

    private static Map<String, Integer> items(ShopClient.Response reservation) {
        Map<String, Integer> items = new TreeMap<>();
        reservation.body().path("items").forEach(i -> items.merge(i.path("sku").asString(), i.path("quantity").asInt(),
                Integer::sum));
        return items;
    }

    private static Map<String, Integer> items(List<OrderMix.Line> lines) {
        Map<String, Integer> items = new TreeMap<>();
        lines.forEach(l -> items.merge(l.sku(), l.quantity(), Integer::sum));
        return items;
    }

    private OrderCheck() {
    }
}
