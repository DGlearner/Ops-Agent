package io.github.dglearner.shop.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=common-test", "management.server.port=0",
                "spring.datasource.hikari.connection-timeout=5000"})
class ShopCommonWebTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Test
    void rendersApiExceptionsAsProblemDetailsWithTraceId() throws Exception {
        HttpResponse<String> response = post("/out-of-stock", "{\"sku_code\":\"SKU-1\",\"quantity\":1}", "key-1");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body())
                .contains("\"code\":\"INSUFFICIENT_STOCK\"")
                .contains("\"sku\":\"SKU-1\"");
        assertThat(response.headers().firstValue(TraceIdResponseFilter.HEADER)).hasValueSatisfying(id ->
                assertThat(id).matches("[0-9a-f]{32}"));
    }

    @Test
    void readsAndWritesSnakeCase() throws Exception {
        HttpResponse<String> response = post("/echo", "{\"sku_code\":\"SKU-1\",\"quantity\":3}", "key-1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"sku_code\":\"SKU-1\",\"quantity\":3}");
    }

    @Test
    void reportsValidationErrorsPerField() throws Exception {
        HttpResponse<String> badBody = post("/echo", "{\"sku_code\":\"\",\"quantity\":0}", "key-1");
        HttpResponse<String> noHeader = post("/echo", "{\"sku_code\":\"SKU-1\",\"quantity\":1}", null);
        HttpResponse<String> notJson = post("/echo", "{", "key-1");

        assertThat(badBody.statusCode()).isEqualTo(400);
        assertThat(badBody.body()).contains("VALIDATION_FAILED").contains("sku_code").contains("quantity");
        assertThat(noHeader.statusCode()).isEqualTo(400);
        assertThat(noHeader.body()).contains("VALIDATION_FAILED").contains("Idempotency-Key");
        assertThat(notJson.statusCode()).isEqualTo(400);
        assertThat(notJson.body()).contains("MALFORMED_JSON");
    }

    @Test
    void hidesUnexpectedErrorsFromClientsButLogsThemAsEcsJsonWithTheTraceId() throws Exception {
        HttpResponse<String> response = post("/boom", "{}", "key-1");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain("secret");

        String traceId = response.headers().firstValue(TraceIdResponseFilter.HEADER).orElseThrow();
        Path logFile = Path.of(environment.getRequiredProperty("logging.file.name"));
        String line = Files.readAllLines(logFile).stream()
                .filter(l -> l.contains("\"message\":\"Unhandled error\"") && l.contains(traceId))
                .findFirst().orElseThrow();
        assertThat(line)
                .contains("\"log\":{\"level\":\"ERROR\"")
                .contains("\"service\":{\"name\":\"common-test\"")
                .contains("\"trace.id\":\"" + traceId + "\"")
                .contains("\"span.id\":")
                .contains("\"event\":{\"action\":\"unhandled_error\"}")
                .contains("\"stack_trace\":\"java.lang.IllegalStateException: secret internals");
    }

    @Test
    void databaseOutagesAreReportedAsRetryable503s() throws Exception {
        for (String failure : new String[] {"no-connection", "link-broken", "lock-timeout"}) {
            HttpResponse<String> response = post("/db/" + failure, "{}", "k");

            assertThat(response.statusCode()).as(failure).isEqualTo(503);
            assertThat(response.body()).contains("\"code\":\"DATABASE_UNAVAILABLE\"");
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        }
        Path logFile = Path.of(environment.getRequiredProperty("logging.file.name"));
        assertThat(Files.readAllLines(logFile).stream()
                .filter(l -> l.contains("\"event\":{\"action\":\"database_unavailable\"}")).count())
                .as("each outage is logged with a stable event name").isGreaterThanOrEqualTo(3);
    }

    @Test
    void sharedDefaultsApplyAndServiceSettingsOverrideThem() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
    }

    @Test
    void injectedDelayBeforeProcessingSlowsOnlyMatchingRequests() throws Exception {
        try {
            assertThat(addFault("{\"path\":\"/echo\",\"delay\":\"300ms\"}").statusCode()).isEqualTo(200);

            assertThat(timed(() -> post("/echo", "{\"sku_code\":\"A\",\"quantity\":1}", "k"))).isGreaterThanOrEqualTo(300);
            assertThat(timed(() -> post("/api/count", "{}", "k"))).isLessThan(300);
        } finally {
            clearFaults();
        }
        assertThat(timed(() -> post("/echo", "{\"sku_code\":\"A\",\"quantity\":1}", "k"))).isLessThan(300);
    }

    @Test
    void injectedErrorBeforeProcessingLooksLikeAnOrdinaryOutageAndChangesNothing() throws Exception {
        int before = COUNT.get();
        try {
            addFault("{\"path\":\"/api/**\",\"method\":\"post\",\"errors\":1.0}");

            HttpResponse<String> response = post("/api/count", "{}", "k");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"").doesNotContain("fault");
            assertThat(COUNT.get()).isEqualTo(before);
        } finally {
            clearFaults();
        }
    }

    @Test
    void injectedFaultsAfterProcessingDelayOrLoseTheAnswer() throws Exception {
        try {
            addFault("{\"path\":\"/api/count\",\"phase\":\"after\",\"errors\":1.0,\"status\":500}");
            int before = COUNT.get();

            HttpResponse<String> lost = post("/api/count", "{}", "k");

            assertThat(lost.statusCode()).isEqualTo(500);
            assertThat(lost.body()).contains("INTERNAL_ERROR");
            assertThat(COUNT.get()).isEqualTo(before + 1);

            clearFaults();
            addFault("{\"path\":\"/api/count\",\"phase\":\"after\",\"delay\":\"300ms\"}");
            long[] took = new long[1];
            HttpResponse<String> late = timedResponse(() -> post("/api/count", "{}", "k"), took);
            assertThat(late.statusCode()).isEqualTo(200);
            assertThat(late.body()).isEqualTo("{\"count\":" + (before + 2) + "}");
            assertThat(took[0]).isGreaterThanOrEqualTo(300);
        } finally {
            clearFaults();
        }
    }

    @Test
    void faultEndpointListsRulesAndRejectsInvalidOnes() throws Exception {
        try {
            addFault("{\"path\":\"/echo\",\"delay\":\"1s\",\"errors\":0.25}");

            String rules = management("GET", null).body();
            assertThat(rules).contains("\"path\":\"/echo\"").contains("\"phase\":\"BEFORE\"")
                    .contains("\"error_rate\":0.25");
            assertThat(addFault("{\"path\":\"/echo\",\"status\":404}").statusCode()).isEqualTo(400);
            assertThat(addFault("{\"path\":\"/echo\",\"errors\":2}").statusCode()).isEqualTo(400);
        } finally {
            clearFaults();
        }
        assertThat(management("GET", null).body()).isEqualTo("[]");
    }

    @Test
    void faultRulesExpireOnTheirOwn() throws Exception {
        try {
            HttpResponse<String> added = addFault("{\"path\":\"/echo\",\"errors\":1.0,\"duration\":\"500ms\"}");
            assertThat(added.statusCode()).isEqualTo(200);
            assertThat(added.body()).contains("\"expires_at\"");
            assertThat(post("/echo", "{\"sku_code\":\"SKU-1\",\"quantity\":1}", "k").statusCode()).isEqualTo(503);

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(management("GET", null).body()).isEqualTo("[]"));
            assertThat(post("/echo", "{\"sku_code\":\"SKU-1\",\"quantity\":1}", "k").statusCode()).isEqualTo(200);

            assertThat(addFault("{\"path\":\"/echo\",\"duration\":\"0s\"}").statusCode()).isEqualTo(400);
            assertThat(addFault("{\"path\":\"/echo\",\"duration\":\"2h\"}").statusCode()).isEqualTo(400);
            // Without a duration a rule stays for the default 10 minutes, not forever.
            assertThat(addFault("{\"path\":\"/echo\"}").body()).contains("\"expires_at\"");
        } finally {
            clearFaults();
        }
    }

    @Test
    void businessPortIsReachableFromThisMachineOnly() throws Exception {
        InetAddress external = NetworkInterface.networkInterfaces()
                .filter(nic -> isUp(nic) && !isLoopback(nic))
                .flatMap(NetworkInterface::inetAddresses)
                .filter(Inet4Address.class::isInstance)
                .findFirst().orElse(null);
        assumeTrue(external != null, "this machine has no non-loopback IPv4 address");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
        }
        try (Socket socket = new Socket()) {
            assertThatThrownBy(() -> socket.connect(new InetSocketAddress(external, port), 1000))
                    .isInstanceOf(IOException.class);
        }
    }

    private static boolean isUp(NetworkInterface nic) {
        try {
            return nic.isUp();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isLoopback(NetworkInterface nic) {
        try {
            return nic.isLoopback();
        } catch (IOException e) {
            return true;
        }
    }

    @LocalManagementPort
    private int managementPort;

    private HttpResponse<String> addFault(String json) throws Exception {
        return management("POST", json);
    }

    private void clearFaults() throws Exception {
        management("DELETE", null);
    }

    private HttpResponse<String> management(String method, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/faults"));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long timed(Callable<HttpResponse<String>> call) throws Exception {
        long[] took = new long[1];
        timedResponse(call, took);
        return took[0];
    }

    private static HttpResponse<String> timedResponse(Callable<HttpResponse<String>> call, long[] took)
            throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = call.call();
        took[0] = (System.nanoTime() - start) / 1_000_000;
        return response;
    }

    private HttpResponse<String> post(String path, String json, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final AtomicInteger COUNT = new AtomicInteger();

    record Line(@NotBlank String skuCode, @Min(1) int quantity) {
    }

    @SpringBootApplication
    @RestController
    static class TestApp {

        @Value("${spring.application.name}")
        private String name;

        @PostMapping("/echo")
        Line echo(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Line line) {
            return line;
        }

        @PostMapping("/out-of-stock")
        Line outOfStock(@RequestBody Line line) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Not enough stock")
                    .with("sku", line.skuCode());
        }

        @PostMapping("/boom")
        Line boom() {
            throw new IllegalStateException("secret internals");
        }

        @PostMapping("/db/{failure}")
        Line database(@PathVariable String failure) {
            throw switch (failure) {
                case "no-connection" -> new CannotCreateTransactionException("Could not open JDBC Connection");
                case "link-broken" -> new RecoverableDataAccessException("Communications link failure");
                default -> new CannotAcquireLockException("Lock wait timeout exceeded");
            };
        }

        @PostMapping("/api/count")
        Map<String, Integer> count() {
            return Map.of("count", COUNT.incrementAndGet());
        }
    }
}
