package io.github.dglearner.shop.loadgen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs a scenario's fault while orders keep flowing: waits for the baseline, injects, waits for the fault phase,
 * clears. Records when each step happened; that timeline is the ground truth in the run report.
 *
 * <p>A fault is recorded as cleared only when every clearing step succeeded. Fault rules are injected with an expiry a
 * little after the planned end of the fault, so even if this process dies before clearing them they end on their
 * own; a stopped container does not, and is reported.
 */
final class ScenarioRunner {

    /** How long an injected fault rule outlives the planned fault phase if it is never cleared. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    /** How long a docker command may run before it is stopped and counted as failed. */
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    private final Scenarios.Scenario scenario;
    private final Options options;
    private final ShopClient client;
    private final Thread thread;
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final CountDownLatch stop = new CountDownLatch(1);
    private volatile Instant injectedAt;
    private volatile Instant clearedAt;
    private boolean clearAttempted;

    ScenarioRunner(Scenarios.Scenario scenario, Options options, ShopClient client) {
        this.scenario = scenario;
        this.options = options;
        this.client = client;
        this.thread = Thread.ofPlatform().name("scenario").daemon().unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    private void run() {
        // Waits on a latch rather than sleeping, so finish() can end a phase early without interrupting this thread:
        // the clearing requests below would fail at once on an interrupted thread.
        try {
            if (stop.await(options.baseline().toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
            injectedAt = Instant.now();
            System.out.printf("%s fault injected: %s%n", injectedAt, scenario.fault());
            perform("inject", scenario.inject());
            stop.await(options.fault().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (injectedAt != null) {
                clear();
            }
        }
    }

    private synchronized void clear() {
        if (clearAttempted) {
            return;
        }
        clearAttempted = true;
        if (perform("clear", scenario.clear())) {
            clearedAt = Instant.now();
            System.out.printf("%s fault cleared%n", clearedAt);
        } else {
            System.out.printf("%s FAULT NOT CLEARED, it may still be active: %s%n", Instant.now(), scenario.fault());
            System.out.println("  injected fault rules expire on their own " + EXPIRY_MARGIN.toSeconds()
                    + " s after the planned end of the fault; repeat the failed steps above for anything else");
        }
    }

    /**
     * Ends the scenario, clearing the fault if it is still active, e.g. when the run was cut short or the process is
     * shutting down. Safe to call more than once.
     */
    void finish() {
        stop.countDown();
        try {
            // Long enough for a clearing command to run into its own timeout and be reported.
            thread.join(COMMAND_TIMEOUT.plusSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("name", scenario.name());
        report.put("target_service", scenario.targetService());
        report.put("fault", scenario.fault());
        report.put("expected", scenario.expected());
        report.put("baseline_s", options.baseline().toSeconds());
        report.put("fault_s", options.fault().toSeconds());
        report.put("recovery_s", options.recovery().toSeconds());
        report.put("injected_at", injectedAt == null ? null : injectedAt.toString());
        report.put("cleared_at", clearedAt == null ? null : clearedAt.toString());
        report.put("steps", steps);
        return report;
    }

    boolean ok() {
        return injectedAt != null && clearedAt != null && steps.stream().allMatch(s -> Boolean.TRUE.equals(s.get("ok")));
    }

    /** Runs the actions in order; true if all succeeded. */
    private boolean perform(String phase, List<Scenarios.Action> actions) {
        boolean ok = true;
        for (Scenarios.Action action : actions) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("phase", phase);
            step.put("at", Instant.now().toString());
            switch (action) {
                case Scenarios.Action.Http http -> {
                    String body = http.body() == null ? null
                            : http.body().replace("%FAULT%", options.fault().toMillis() + "ms")
                                    .replace("%EXPIRY%", options.fault().plus(EXPIRY_MARGIN).toMillis() + "ms");
                    ShopClient.Response response = client.admin(http.service(), http.method(), http.path(), body);
                    step.put("action", http.method() + " " + http.service() + " " + http.path());
                    step.put("body", body);
                    step.put("ok", response.status() == 200 || response.status() == 204);
                    step.put("status", response.status());
                }
                case Scenarios.Action.Docker docker -> {
                    List<String> command = new ArrayList<>(List.of("docker"));
                    for (String arg : docker.args()) {
                        command.add(arg.replace("%MYSQL%", options.mysqlContainer()));
                    }
                    step.put("action", String.join(" ", command));
                    step.put("ok", runCommand(command, COMMAND_TIMEOUT));
                }
            }
            if (!Boolean.TRUE.equals(step.get("ok"))) {
                System.out.println("  step failed: " + step);
                ok = false;
            }
            steps.add(step);
        }
        return ok;
    }

    /** Runs a command; false if it cannot start, exits non-zero, or is still running at the timeout (it is killed). */
    static boolean runCommand(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            System.out.println("  cannot run " + command.getFirst() + ": " + e.getMessage());
            return false;
        }
        // The output is read on its own thread: reading it here would wait until the process closes it, however long
        // that takes, before the timeout below even starts.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().name("command-output").daemon().start(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException e) {
                // The process was killed; whatever was read so far is reported.
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                System.out.println("  " + String.join(" ", command) + " still running after " + timeout.toSeconds()
                        + " s, stopped: " + output.toString(StandardCharsets.UTF_8).trim());
                return false;
            }
            reader.join(Duration.ofSeconds(5));
            if (process.exitValue() != 0) {
                System.out.println("  " + String.join(" ", command) + " failed: "
                        + output.toString(StandardCharsets.UTF_8).trim());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Duration total(Options options) {
        return options.baseline().plus(options.fault()).plus(options.recovery());
    }
}
