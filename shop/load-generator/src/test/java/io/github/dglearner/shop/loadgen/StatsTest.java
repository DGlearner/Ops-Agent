package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.MissingNode;

class StatsTest {

    @Test
    void percentilesUseNearestRankOverOriginalRequestsOnly() {
        Stats stats = new Stats();
        for (int ms = 1; ms <= 100; ms++) {
            stats.response(new ShopClient.Response(201, MissingNode.getInstance(), "", ms, null), true);
        }
        stats.response(new ShopClient.Response(200, MissingNode.getInstance(), "", 10_000, null), false);

        assertThat(stats.percentile(50)).isEqualTo(50);
        assertThat(stats.percentile(99)).isEqualTo(99);
        assertThat(stats.statusCounts()).containsEntry(201, 100L).containsEntry(200, 1L);
    }
}
