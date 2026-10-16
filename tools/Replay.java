import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Replays a JSONL dataset into ingest-service, one request per line.
 *
 * <pre>
 * java tools/Replay.java [--url http://localhost:8081] [--key dev-ingest-key] [--rate 20] [--run-id ID] FILE
 * </pre>
 *
 * Runs on any OS with JDK 21 (single-file source launch, no build needed). Each request carries
 * Idempotency-Key "run-id:line", so retries after 429/503 within a run, or rerunning with the same --run-id,
 * produce the same event ids.
 */
public class Replay {

    private static final int MAX_ATTEMPTS = 5;

    public static void main(String[] args) throws IOException, InterruptedException {
        String url = "http://localhost:8081";
        String key = "dev-ingest-key";
        double rate = 20;
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Path file = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = args[++i];
                case "--key" -> key = args[++i];
                case "--rate" -> rate = Double.parseDouble(args[++i]);
                case "--run-id" -> runId = args[++i];
                default -> file = Path.of(args[i]);
            }
        }
        if (file == null) {
            System.err.println("usage: java tools/Replay.java [--url URL] [--key KEY] [--rate RPS] [--run-id ID] FILE");
            System.exit(2);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<String> lines = Files.readAllLines(file);
        long pauseMillis = rate > 0 ? (long) (1000 / rate) : 0;
        int accepted = 0;
        int failed = 0;
        for (int n = 0; n < lines.size(); n++) {
            String body = lines.get(n).strip();
            if (body.isEmpty()) {
                continue;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/v1/logs"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", key)
                    .header("Idempotency-Key", runId + ":" + (n + 1))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = send(http, request);
            if (response.statusCode() == 202) {
                accepted++;
            } else {
                failed++;
                System.err.printf("line %d: HTTP %d %s%n", n + 1, response.statusCode(), response.body());
            }
            if (pauseMillis > 0) {
                Thread.sleep(pauseMillis);
            }
        }
        System.out.printf("run %s: %d requests accepted, %d failed%n", runId, accepted, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Retries 429 and 503 using the server's Retry-After. */
    private static HttpResponse<String> send(HttpClient http, HttpRequest request)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            boolean retryable = response.statusCode() == 429 || response.statusCode() == 503;
            if (!retryable || attempt == MAX_ATTEMPTS) {
                return response;
            }
            long seconds = response.headers().firstValueAsLong("Retry-After").orElse(1);
            Thread.sleep(Duration.ofSeconds(seconds));
        }
    }
}
