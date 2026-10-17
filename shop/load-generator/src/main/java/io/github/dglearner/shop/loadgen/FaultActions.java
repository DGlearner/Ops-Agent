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
import java.util.concurrent.TimeUnit;

/**
 * Carries out a scenario's inject or clear actions: calls to a service's management port, or docker commands. Shared
 * by the scenario runner and the lab console.
 */
public final class FaultActions {

    /** How long a docker command may run before it is stopped and counted as failed. */
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /** How long an injected fault rule outlives the planned fault if it is never cleared. */
    public static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final ShopClient client;
    private final String mysqlContainer;

    public FaultActions(ShopClient client, String mysqlContainer) {
        this.client = client;
        this.mysqlContainer = mysqlContainer;
    }

    /**
     * Runs the actions in order, for a fault planned to last {@code fault}, and returns one record per action: phase,
     * time, action, whether it succeeded, and the HTTP status or command it ran.
     */
    public List<Map<String, Object>> perform(String phase, List<Scenarios.Action> actions, Duration fault) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Scenarios.Action action : actions) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("phase", phase);
            step.put("at", Instant.now().toString());
            switch (action) {
                case Scenarios.Action.Http http -> {
                    String body = http.body() == null ? null
                            : http.body().replace("%FAULT%", fault.toMillis() + "ms")
                                    .replace("%EXPIRY%", fault.plus(EXPIRY_MARGIN).toMillis() + "ms");
                    ShopClient.Response response = client.admin(http.service(), http.method(), http.path(), body);
                    step.put("action", http.method() + " " + http.service() + " " + http.path());
                    step.put("body", body);
                    step.put("ok", response.status() == 200 || response.status() == 204);
                    step.put("status", response.status());
                }
                case Scenarios.Action.Docker docker -> {
                    List<String> command = new ArrayList<>(List.of("docker"));
                    for (String arg : docker.args()) {
                        command.add(arg.replace("%MYSQL%", mysqlContainer));
                    }
                    step.put("action", String.join(" ", command));
                    step.put("ok", runCommand(command, COMMAND_TIMEOUT));
                }
            }
            if (!Boolean.TRUE.equals(step.get("ok"))) {
                System.out.println("  step failed: " + step);
            }
            steps.add(step);
        }
        return steps;
    }

    public static boolean allOk(List<Map<String, Object>> steps) {
        return steps.stream().allMatch(s -> Boolean.TRUE.equals(s.get("ok")));
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
}
