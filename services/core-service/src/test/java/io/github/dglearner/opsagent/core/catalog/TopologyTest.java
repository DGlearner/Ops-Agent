package io.github.dglearner.opsagent.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopologyTest {

    private final Topology shop = new Topology(Map.of(), Map.of(
            "order-service", List.of("inventory-service", "payment-service", "shop-mysql"),
            "inventory-service", List.of("shop-mysql"),
            "payment-service", List.of("shop-mysql")));

    @Test
    void closureFollowsDependenciesTransitively() {
        assertThat(shop.dependencyClosure("order-service"))
                .containsExactlyInAnyOrder("order-service", "inventory-service", "payment-service", "shop-mysql");
        assertThat(shop.dependencyClosure("shop-mysql")).containsExactly("shop-mysql");
        assertThat(shop.dependencyClosure("unknown")).containsExactly("unknown");
    }

    @Test
    void aSharedDependencyIsNoPathBetweenTwoServices() {
        assertThat(shop.dependencyPathBetween("order-service", "shop-mysql")).isTrue();
        assertThat(shop.dependencyPathBetween("shop-mysql", "payment-service")).isTrue();
        assertThat(shop.dependencyPathBetween("payment-service", "inventory-service")).isFalse();
    }

    @Test
    void cyclesEndTheWalk() {
        Topology cycle = new Topology(Map.of(), Map.of("a", List.of("b"), "b", List.of("a")));

        assertThat(cycle.dependencyClosure("a")).containsExactlyInAnyOrder("a", "b");
    }
}
