package io.github.dglearner.opsagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CatalogIT extends CoreTestSupport {

    @Test
    void theShopTopologyIsSeededInTheLocalProfile() throws Exception {
        JsonNode topology = getJson("/api/v1/topology");

        assertThat(edges(topology)).contains("order-service->inventory-service", "order-service->payment-service",
                "inventory-service->shop-mysql", "payment-service->shop-mysql");
        assertThat(topology.path("nodes").findValuesAsString("kind")).contains("database");
    }

    @Test
    void dependenciesAreReplacedAsAWholeAndMustBeKnown() throws Exception {
        String api = "api-" + UUID.randomUUID().toString().substring(0, 8);
        String db = "db-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(put("/api/v1/services/" + api, "{\"kind\":\"service\",\"description\":\"x\"}").statusCode())
                .isEqualTo(200);
        assertThat(put("/api/v1/services/" + db, "{\"kind\":\"database\"}").statusCode()).isEqualTo(200);

        assertThat(put("/api/v1/services/" + api + "/dependencies", "{\"depends_on\":[\"" + db + "\"]}").statusCode())
                .isEqualTo(200);
        assertThat(edges(getJson("/api/v1/topology"))).contains(api + "->" + db);
        assertThat(put("/api/v1/services/" + api + "/dependencies", "{\"depends_on\":[]}").statusCode())
                .isEqualTo(200);
        assertThat(edges(getJson("/api/v1/topology"))).doesNotContain(api + "->" + db);

        assertThat(put("/api/v1/services/" + api + "/dependencies", "{\"depends_on\":[\"nope\"]}").body())
                .contains("UNKNOWN_SERVICE");
        assertThat(put("/api/v1/services/nope/dependencies", "{\"depends_on\":[]}").statusCode()).isEqualTo(404);
        assertThat(put("/api/v1/services/" + api + "/dependencies", "{\"depends_on\":[\"" + api + "\"]}").body())
                .contains("SELF_DEPENDENCY");
        assertThat(put("/api/v1/services/" + api, "{\"kind\":\"mainframe\"}").statusCode()).isEqualTo(400);
    }

    private static List<String> edges(JsonNode topology) {
        List<String> edges = new ArrayList<>();
        topology.path("edges").forEach(e -> edges.add(e.path("from").asString() + "->" + e.path("to").asString()));
        return edges;
    }
}
