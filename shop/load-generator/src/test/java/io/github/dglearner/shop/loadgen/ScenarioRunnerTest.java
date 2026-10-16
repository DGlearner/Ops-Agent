package io.github.dglearner.shop.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Scenario timeline against a stand-in management port that records what it was asked to do. */
class ScenarioRunnerTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile int clearStatus = 200;
    private HttpServer admin;

    @BeforeEach
    void startAdmin() throws IOException {
        admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admin.createContext("/actuator/faults", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestMethod() + " " + body);
            int status = exchange.getRequestMethod().equals("DELETE") ? clearStatus : 200;
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        admin.start();
    }

    @AfterEach
    void stopAdmin() {
        admin.stop(0);
    }

    @Test
    void injectsAnExpiringFaultAndClearsItAfterTheFaultPhase() {
        ScenarioRunner runner = runner("100ms", "300ms");

        runner.start();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
        runner.finish();

        // The rule expires on its own 30 s after the planned 300 ms fault, in case it is never cleared.
        assertThat(received.get(0)).startsWith("POST ").contains("\"duration\":\"30300ms\"");
        assertThat(received.get(1)).isEqualTo("DELETE ");
        assertThat(runner.report().get("cleared_at")).isNotNull();
        assertThat(runner.ok()).isTrue();
    }

    @Test
    void runCutShortDuringTheFaultStillClearsIt() {
        ScenarioRunner runner = runner("100ms", "5m");

        runner.start();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
        long start = System.nanoTime();
        runner.finish();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(received).hasSize(2).last().isEqualTo("DELETE ");
        assertThat(runner.report().get("cleared_at")).isNotNull();
        assertThat(runner.ok()).isTrue();
    }

    @Test
    void failedClearIsNotRecordedAsCleared() {
        clearStatus = 500;
        ScenarioRunner runner = runner("100ms", "5m");

        runner.start();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
        runner.finish();

        assertThat(received).hasSize(2);
        Map<String, Object> report = runner.report();
        assertThat(report.get("injected_at")).isNotNull();
        assertThat(report.get("cleared_at")).isNull();
        assertThat(runner.ok()).isFalse();
    }

    @Test
    void runCutShortDuringTheBaselineInjectsNothing() {
        ScenarioRunner runner = runner("5m", "5m");

        runner.start();
        runner.finish();

        assertThat(received).isEmpty();
        assertThat(runner.report().get("injected_at")).isNull();
        assertThat(runner.ok()).isFalse();
    }

    @Test
    void commandStillRunningAtTheTimeoutIsKilledAndFails() {
        long start = System.nanoTime();

        boolean ok = ScenarioRunner.runCommand(sleeper(120, 0), Duration.ofSeconds(2));

        assertThat(ok).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> ProcessHandle.allProcesses()
                .noneMatch(p -> p.info().commandLine().orElse("").contains(Sleeper.class.getName())));
    }

    @Test
    void commandResultFollowsItsExitCode() {
        assertThat(ScenarioRunner.runCommand(sleeper(0, 0), Duration.ofSeconds(30))).isTrue();
        assertThat(ScenarioRunner.runCommand(sleeper(0, 3), Duration.ofSeconds(30))).isFalse();
        assertThat(ScenarioRunner.runCommand(List.of("no-such-command-for-the-shop"), Duration.ofSeconds(5))).isFalse();
    }

    /** {@link Sleeper} in a child JVM; only the test classes are needed on its class path. */
    private static List<String> sleeper(int seconds, int exitCode) {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classes;
        try {
            classes = Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return List.of(java, "-Xmx32m", "-cp", classes, Sleeper.class.getName(), String.valueOf(seconds),
                String.valueOf(exitCode));
    }

    private ScenarioRunner runner(String baseline, String fault) {
        Options options = Options.parse("--scenario", "payment-errors", "--baseline", baseline, "--fault", fault,
                "--payment-admin", "http://127.0.0.1:" + admin.getAddress().getPort());
        return new ScenarioRunner(Scenarios.get("payment-errors"), options,
                new ShopClient(options, JsonMapper.builder().build()));
    }
}
