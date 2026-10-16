package io.github.dglearner.shop.loadgen;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What a run was measured on (docs/shop-design.md §11.5): code version, machine and whether the generator shares it
 * with the services; and MySQL's row lock counters, read before and after the run. Everything here is best effort:
 * what cannot be found out is reported as unknown rather than failing the run.
 */
final class RunEnvironment {

    static Map<String, Object> describe(Options options) {
        Map<String, Object> env = new LinkedHashMap<>();
        String commit = command("git", "rev-parse", "--short", "HEAD");
        env.put("git_commit", commit == null ? "unknown" : commit);
        String changes = command("git", "status", "--porcelain", "--untracked-files=no");
        env.put("git_uncommitted_changes", changes == null ? "unknown" : !changes.isEmpty());
        env.put("java", Runtime.version().toString());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("cpus", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            env.put("memory_mb", os.getTotalMemorySize() / (1024 * 1024));
        }
        env.put("generator_on_the_same_host", List.of(options.orderUrl(), options.inventoryUrl(), options.paymentUrl())
                .stream().allMatch(RunEnvironment::isLocal));
        return env;
    }

    /**
     * InnoDB's row lock counters ({@code Innodb_row_lock_waits}, {@code Innodb_row_lock_time} in ms), read through
     * the MySQL container with the inventory service's development account; empty if that is not possible.
     */
    static Map<String, Long> rowLocks(String container) {
        String output = command("docker", "exec", container, "mysql", "-uinventory_svc", "-pinventory-dev", "-N", "-e",
                "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time')");
        Map<String, Long> counters = new LinkedHashMap<>();
        if (output == null) {
            return counters;
        }
        for (String line : output.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].startsWith("Innodb_")) {
                counters.put(parts[0].toLowerCase(), Long.parseLong(parts[1]));
            }
        }
        return counters;
    }

    private static boolean isLocal(String url) {
        String host = URI.create(url).getHost();
        return host != null && (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]"));
    }

    /** Standard output of a short command, or null if it cannot be run, fails or takes more than 10 s. */
    private static String command(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<String> lines = new ArrayList<>();
            Thread reader = Thread.ofPlatform().daemon().start(() -> {
                try {
                    lines.addAll(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                            .toList());
                } catch (IOException e) {
                    // Killed below.
                }
            });
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            reader.join(TimeUnit.SECONDS.toMillis(2));
            return process.exitValue() == 0 ? String.join("\n", lines).trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private RunEnvironment() {
    }
}
