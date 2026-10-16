package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OrderMixTest {

    private static final List<String> SKUS = List.of("SKU-1001", "SKU-1002", "SKU-1003", OrderMix.HOT_SKU);

    @Test
    void ordersHaveDistinctSkusAndFollowTheConfiguredShares() {
        Options options = Options.parse("--hot-rate", "0.2", "--decline-rate", "0.1", "--users", "5");
        OrderMix mix = new OrderMix(SKUS, options, new Random(7));

        List<OrderMix.OrderRequest> orders = IntStream.range(0, 5_000).mapToObj(i -> mix.next()).toList();

        assertThat(orders).allSatisfy(o -> {
            assertThat(o.lines()).extracting(OrderMix.Line::sku).doesNotHaveDuplicates();
            assertThat(o.lines()).allSatisfy(l -> assertThat(l.quantity()).isBetween(1, 3));
            assertThat(o.userId()).matches("u-100[0-4]");
        });
        double hot = orders.stream().filter(o -> o.lines().stream().anyMatch(l -> l.sku().equals(OrderMix.HOT_SKU)))
                .count() / 5_000.0;
        double declined = orders.stream().filter(o -> o.paymentToken().equals("tok_declined")).count() / 5_000.0;
        assertThat(hot).isBetween(0.17, 0.23);
        assertThat(declined).isBetween(0.08, 0.12);
    }

    @Test
    void theSameSeedGivesTheSameOrders() {
        Options options = Options.parse();
        OrderMix a = new OrderMix(SKUS, options, new Random(42));
        OrderMix b = new OrderMix(SKUS, options, new Random(42));

        for (int i = 0; i < 100; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }
}
