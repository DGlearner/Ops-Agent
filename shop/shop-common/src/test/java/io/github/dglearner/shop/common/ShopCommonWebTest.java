package io.github.dglearner.shop.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=common-test", "management.server.port=0",
                "spring.datasource.hikari.connection-timeout=5s"})
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
                .contains("\"stack_trace\":\"java.lang.IllegalStateException: secret internals");
    }

    @Test
    void sharedDefaultsApplyAndServiceSettingsOverrideThem() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5s");
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
    }
}
