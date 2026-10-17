package io.github.dglearner.opsagent.shipper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code POST /api/v1/logs} of ingest-service, for one batch of lines of one file. */
final class IngestClient {

    /** How a batch was answered. */
    sealed interface Result {
        /** Accepted (202): the lines are on Kafka. */
        record Accepted() implements Result {
        }

        /** Not now (429, 5xx or no answer): send the same batch again after {@code after}, or later if null. */
        record Retry(Duration after, String reason) implements Result {
        }

        /** Refused for good (another 4xx): sending it again would be refused again. */
        record Rejected(String reason) implements Result {
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final ShipperOptions options;

    IngestClient(ShipperOptions options) {
        this.options = options;
    }

    /**
     * Sends the lines of one file. {@code idempotencyKey} must be the same for every attempt at this batch: ingest
     * derives the lines' event ids from it, so a batch sent twice is counted once downstream.
     */
    Result send(String service, String sourceName, List<String> lines, String idempotencyKey) {
        ObjectNode body = json.createObjectNode();
        body.put("source_type", "app_service");
        body.put("host", options.host());
        body.put("service", service);
        body.put("source_name", sourceName);
        ArrayNode logs = body.putArray("logs");
        lines.forEach(logs::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(options.url() + "/api/v1/logs"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", options.apiKey())
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new Result.Retry(null, "no answer from " + options.url() + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Retry(null, "interrupted");
        }
        int status = response.statusCode();
        if (status == 202) {
            return new Result.Accepted();
        }
        String reason = "HTTP " + status + " " + response.body();
        if (status == 429 || status >= 500) {
            Duration after = response.headers().firstValue("Retry-After").map(IngestClient::seconds).orElse(null);
            return new Result.Retry(after, reason);
        }
        return new Result.Rejected(reason);
    }

    private static Duration seconds(String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
