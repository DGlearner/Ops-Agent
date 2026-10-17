package io.github.dglearner.opsagent.shipper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shipper against a stand-in for ingest-service that records every request. */
class LogShipperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One request as ingest received it. */
    record Received(String key, String service, String sourceName, List<String> lines) {
    }

    @TempDir
    Path dir;

    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    /** How many of the next requests to answer with 503 before accepting. */
    private final AtomicInteger failNext = new AtomicInteger();
    private HttpServer ingest;

    @BeforeEach
    void startIngest() throws IOException {
        ingest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingest.createContext("/api/v1/logs", exchange -> {
            JsonNode body = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            body.path("logs").forEach(l -> lines.add(l.asString()));
            received.add(new Received(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    body.path("service").asString(), body.path("source_name").asString(), lines));
            boolean fail = failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
            if (fail) {
                exchange.getResponseHeaders().add("Retry-After", "0");
            }
            exchange.sendResponseHeaders(fail ? 503 : 202, -1);
            exchange.close();
        });
        ingest.start();
    }

    @AfterEach
    void stopIngest() {
        ingest.stop(0);
    }

    @Test
    void shipsCompleteLinesOnlyAndResumesAfterARestart() throws IOException {
        Path log = dir.resolve("order-service.log");
        Files.writeString(log, "one\ntwo\nthree, still being writ");

        ship("--from-start");
        append(log, "ten\nfour\n");
        ship();

        assertThat(shippedLines()).containsExactly("one", "two", "three, still being written", "four");
        assertThat(received).allSatisfy(r -> {
            assertThat(r.service()).isEqualTo("order-service");
            assertThat(r.sourceName()).endsWith("/order-service.log");
        });
    }

    @Test
    void startsAtTheEndOfFilesThatAreThereUnlessToldOtherwise() throws IOException {
        Path log = dir.resolve("order-service.log");
        Files.writeString(log, "old\n");

        ship();
        append(log, "new\n");
        ship();

        assertThat(shippedLines()).containsExactly("new");
    }

    @Test
    void dropsTheFaultInjectionLogger() throws IOException {
        Files.writeString(dir.resolve("payment-service.log"), """
                {"log":{"level":"INFO","logger":"io.github.dglearner.shop.payment.PaymentService"},"message":"a"}
                {"log":{"level":"INFO","logger":"shop.faults"},"message":"Fault rule added"}
                {"log":{"level":"INFO","logger":"io.github.dglearner.shop.payment.PaymentService"},"message":"b"}
                """);

        ship("--from-start");

        assertThat(shippedLines()).hasSize(2).noneMatch(l -> l.contains("shop.faults"));
    }

    @Test
    void retriesTheSameBatchWithTheSameKey() throws IOException {
        Files.writeString(dir.resolve("order-service.log"), "a\nb\n");
        failNext.set(2);

        ship("--from-start");

        assertThat(received).hasSize(3);
        assertThat(received).extracting(Received::key).containsOnly(received.peek().key());
        assertThat(received).extracting(Received::lines).containsOnly(List.of("a", "b"));
    }

    @Test
    void readsARotatedOrTruncatedFileFromItsStart() throws IOException {
        Path log = dir.resolve("inventory-service.log");
        Files.writeString(log, "before rotation\n");
        ship("--from-start");

        Files.move(log, dir.resolve("inventory-service.log.2026-10-17.0"));
        Files.writeString(log, "after rotation\n");
        ship();
        Files.writeString(log, "cut\n");
        ship();

        assertThat(shippedLines()).containsExactly("before rotation", "after rotation", "cut");
        assertThat(received).extracting(Received::key).doesNotHaveDuplicates();
    }

    @Test
    void cutsLinesLongerThanIngestAccepts() throws IOException {
        Files.writeString(dir.resolve("order-service.log"), "x".repeat(40_000) + "\nnext\n");

        ship("--from-start");

        List<String> lines = shippedLines();
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).hasSizeLessThanOrEqualTo(16 * 1024).endsWith(FileTail.TRUNCATED);
        assertThat(lines.getLast()).isEqualTo("next");
    }

    private void ship(String... extra) throws IOException {
        List<String> args = new ArrayList<>(List.of("--dir", dir.toString(), "--url",
                "http://127.0.0.1:" + ingest.getAddress().getPort(), "--host", "test-host", "--once"));
        args.addAll(List.of(extra));
        new LogShipper(ShipperOptions.parse(args.toArray(String[]::new))).run();
    }

    private List<String> shippedLines() {
        List<String> lines = new ArrayList<>();
        // A retried batch is in the list more than once; count each key once.
        received.stream().map(Received::key).distinct()
                .forEach(key -> lines.addAll(received.stream().filter(r -> r.key().equals(key)).findFirst()
                        .orElseThrow().lines()));
        return lines;
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardOpenOption.APPEND);
    }
}
