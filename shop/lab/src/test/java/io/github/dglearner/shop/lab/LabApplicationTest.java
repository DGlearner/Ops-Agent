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
        properties = "shop.lab.ground-truth=build/test-ground-truth/ground-truth.jsonl")
class LabApplicationTest {

    @LocalServerPort
    int port;

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
