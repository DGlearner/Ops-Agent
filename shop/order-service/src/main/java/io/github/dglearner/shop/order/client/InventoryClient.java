package io.github.dglearner.shop.order.client;

import io.github.dglearner.shop.order.order.OrderLine;
import java.io.IOException;
import java.util.List;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls inventory-service; every outcome is a value, never an exception. */
public class InventoryClient {

    public sealed interface ReserveResult {
        record Reserved() implements ReserveResult {
        }

        record OutOfStock(String sku) implements ReserveResult {
        }

        record Failed(CallFailure failure) implements ReserveResult {
        }
    }

    public sealed interface SettleResult {
        record Done() implements SettleResult {
        }

        /** Inventory refused, e.g. confirming a released reservation; retrying will not help. */
        record Conflict(String code) implements SettleResult {
        }

        record Failed(CallFailure failure) implements SettleResult {
        }
    }

    private record ReserveBody(String orderId, List<OrderLine> items) {
    }

    private final RestClient rest;
    private final JsonMapper json;

    public InventoryClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    public ReserveResult reserve(String orderId, List<OrderLine> lines) {
        try {
            return rest.post().uri("/api/v1/reservations").body(new ReserveBody(orderId, lines))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 201 || status == 200) {
                            return new ReserveResult.Reserved();
                        }
                        JsonNode problem = problem(response);
                        String code = problem.path("code").asString();
                        if (status == 409 && code.equals("INSUFFICIENT_STOCK")) {
                            return new ReserveResult.OutOfStock(problem.path("sku").asString());
                        }
                        return new ReserveResult.Failed(CallFailure.response(status, code));
                    });
        } catch (ResourceAccessException e) {
            return new ReserveResult.Failed(CallFailure.from(e));
        }
    }

    public SettleResult confirm(String orderId) {
        return settle("/api/v1/reservations/{orderId}/confirm", orderId);
    }

    public SettleResult release(String orderId) {
        return settle("/api/v1/reservations/{orderId}/release", orderId);
    }

    private SettleResult settle(String uri, String orderId) {
        try {
            return rest.post().uri(uri, orderId).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 200) {
                    return new SettleResult.Done();
                }
                String code = problem(response).path("code").asString();
                if (status == 409 || status == 404) {
                    return new SettleResult.Conflict(code);
                }
                return new SettleResult.Failed(CallFailure.response(status, code));
            });
        } catch (ResourceAccessException e) {
            return new SettleResult.Failed(CallFailure.from(e));
        }
    }

    private JsonNode problem(ClientHttpResponse response) throws IOException {
        return Problems.read(json, response);
    }
}
