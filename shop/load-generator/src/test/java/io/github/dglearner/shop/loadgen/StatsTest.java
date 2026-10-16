package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

class StatsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void namesWhatAResponseSaid() {
        assertThat(Stats.outcome(response(201, "{\"status\":\"PAID\"}"))).isEqualTo("PAID");
        assertThat(Stats.outcome(response(201, "{\"status\":\"CANCELLED\",\"failure_reason\":\"OUT_OF_STOCK\"}")))
                .isEqualTo("CANCELLED/OUT_OF_STOCK");
        assertThat(Stats.outcome(response(202, "{\"status\":\"PAYMENT_UNKNOWN\"}"))).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(Stats.outcome(response(503, "{\"code\":\"INVENTORY_UNAVAILABLE\"}")))
                .isEqualTo("HTTP_503/INVENTORY_UNAVAILABLE");
        assertThat(Stats.outcome(new ShopClient.Response(0, MissingNode.getInstance(), "", 0, "refused")))
                .isEqualTo("NETWORK_ERROR");
    }

    @Test
    void latencyIsKeptPerOutcomeForOriginalRequestsOnly() {
        Stats stats = new Stats();
        for (int ms = 1; ms <= 100; ms++) {
            stats.response(response(201, "{\"status\":\"PAID\"}", ms), true);
        }
        stats.response(response(201, "{\"status\":\"CANCELLED\",\"failure_reason\":\"OUT_OF_STOCK\"}", 7), true);
        stats.response(response(201, "{\"status\":\"PAID\"}", 9999), false);

        assertThat(stats.latencyByOutcome().get("PAID"))
                .containsEntry("count", 100L).containsEntry("p50", 50L).containsEntry("p95", 95L).containsEntry("p99", 99L);
        assertThat(stats.latencyByOutcome().get("CANCELLED/OUT_OF_STOCK")).containsEntry("count", 1L);
    }

    private static ShopClient.Response response(int status, String body) {
        return response(status, body, 0);
    }

    private static ShopClient.Response response(int status, String body, long latency) {
        return new ShopClient.Response(status, JSON.readTree(body), "", latency, null);
    }
}
