package io.github.dglearner.shop.loadgen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/** The few shop endpoints the generator needs. Network errors come back as status 0, never as exceptions. */
final class ShopClient {

    record Response(int status, JsonNode body, String traceId, long latencyMillis, String error) {

        String orderId() {
            return body.path("order_id").asString();
        }
    }

    record StockRow(String sku, int total, int available, int reserved, int sold) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final JsonMapper json;
    private final Options options;

    ShopClient(Options options, JsonMapper json) {
        this.options = options;
        this.json = json;
    }

    List<String> productSkus() {
        Response response = send(get(options.orderUrl() + "/api/v1/products"));
        require(response, "list products");
        List<String> skus = new ArrayList<>();
        response.body().forEach(p -> skus.add(p.path("sku").asString()));
        return skus;
    }

    Map<String, StockRow> stock() {
        Response response = send(get(options.inventoryUrl() + "/api/v1/stock"));
        require(response, "read stock");
        Map<String, StockRow> stock = new LinkedHashMap<>();
        response.body().forEach(s -> stock.put(s.path("sku").asString(), new StockRow(s.path("sku").asString(),
                s.path("total").asInt(), s.path("available").asInt(), s.path("reserved").asInt(),
                s.path("sold").asInt())));
        return stock;
    }

    Response placeOrder(String idempotencyKey, OrderMix.OrderRequest order) {
        ObjectNode body = json.createObjectNode();
        body.put("user_id", order.userId());
        body.put("payment_token", order.paymentToken());
        var items = body.putArray("items");
        order.lines().forEach(l -> items.addObject().put("sku", l.sku()).put("quantity", l.quantity()));
        return send(HttpRequest.newBuilder(URI.create(options.orderUrl() + "/api/v1/orders"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build());
    }

    Response order(String orderId) {
        return send(get(options.orderUrl() + "/api/v1/orders/" + orderId));
    }

    Response payment(String orderId) {
        return send(get(options.paymentUrl() + "/api/v1/payments/" + orderId));
    }

    /** A call to a service's management port, e.g. to inject or clear a fault. */
    Response admin(String service, String method, String path, String jsonBody) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(options.adminUrls().get(service) + path))
                .timeout(Duration.ofSeconds(15));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody));
        return send(request.build());
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private Response send(HttpRequest request) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000;
            JsonNode body = response.body().isEmpty() ? MissingNode.getInstance() : json.readTree(response.body());
            return new Response(response.statusCode(), body, response.headers().firstValue("X-Trace-Id").orElse(""),
                    latency, null);
        } catch (IOException e) {
            return new Response(0, MissingNode.getInstance(), "", (System.nanoTime() - start) / 1_000_000,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, MissingNode.getInstance(), "", 0, "interrupted");
        }
    }

    private static void require(Response response, String what) {
        if (response.status() != 200) {
            throw new IllegalStateException("Cannot " + what + ": "
                    + (response.error() != null ? response.error() : "HTTP " + response.status()));
        }
    }
}
