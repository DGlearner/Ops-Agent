package io.github.dglearner.shop.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The lab starts without the shop or Ops-Agent running, and lists what it can do. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shop.lab.ground-truth=build/test-ground-truth/ground-truth.jsonl",
                "shop.lab.order-url=http://127.0.0.1:1"})
class LabApplicationTest {

    @LocalServerPort
    int port;

    @Test
    void catalogFailureReturnsAnActionable503InsteadOfPretendingTrafficStarted() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/lab/traffic"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"rate\":3}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("mysql-down").contains("先启动流量");
    }

    @Test
    void servesThePageAndTheScenarios() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                .build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> state = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/lab/state")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(page.body()).contains("故障演练控制台");
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.body()).contains("\"name\":\"mysql-down\"").contains("\"name\":\"independent-faults\"");
    }
}
