package io.github.dglearner.shop.loadgen;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Samples each service's resource gauges through Actuator once a second while orders are being sent, and reports the
 * mean and peak of each: CPU, connection pool, request threads and heap. A gauge a service does not publish, or a
 * service that cannot be reached, is left out.
 *
 * <p>CPU is reported in cores used. A JVM under a CPU quota reports its usage relative to the processors the quota
 * gives it, so each service's {@code process.cpu.usage} is multiplied by its {@code system.cpu.count}. The machine as
 * a whole is read from {@code /proc/stat} (Linux only), and the generator's own use from its process CPU time, so a
 * report shows whether the generator itself was the limit.
 */
final class ResourceSampler {

    /** One gauge: Actuator metric name, optional tag filter and the name used in the report. */
    private record Gauge(String metric, String tag, String label) {
    }

    private static final List<Gauge> GAUGES = List.of(
            new Gauge("process.cpu.usage", null, "process_cpu"),
            new Gauge("system.cpu.count", null, "cpu_count"),
            new Gauge("hikaricp.connections.active", null, "db_connections_active"),
            new Gauge("hikaricp.connections.pending", null, "db_connections_pending"),
            new Gauge("hikaricp.connections.max", null, "db_connections_max"),
            new Gauge("tomcat.threads.busy", null, "request_threads_busy"),
            new Gauge("tomcat.threads.config.max", null, "request_threads_max"),
            new Gauge("jvm.memory.used", "area:heap", "heap_used_mb"));

    private static final class Summary {
        double sum;
        double max = Double.NEGATIVE_INFINITY;
        int samples;

        void add(double value) {
            sum += value;
            max = Math.max(max, value);
            samples++;
        }
    }

    private final ShopClient client;
    private final List<String> services;
    private final Map<String, Map<String, Summary>> summaries = new LinkedHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    ResourceSampler(ShopClient client, List<String> services) {
        this.client = client;
        this.services = services;
        services.forEach(s -> summaries.put(s, new LinkedHashMap<>()));
        this.thread = Thread.ofPlatform().name("resource-sampler").daemon().unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    /** Stops sampling; returns per service and gauge the mean and peak, and how many samples were taken. */
    Map<String, Object> stop() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> report = new LinkedHashMap<>();
        synchronized (summaries) {
            summaries.forEach((service, gauges) -> {
                Map<String, Object> perGauge = new LinkedHashMap<>();
                gauges.forEach((label, s) -> perGauge.put(label, Map.of("mean", round(s.sum / s.samples),
                        "max", round(s.max), "samples", s.samples)));
                report.put(service, perGauge);
            });
        }
        return report;
    }

    private void run() {
        long[] machineBefore = machineTicks();
        long generatorCpuBefore = generatorCpuNanos();
        long wallBefore = System.nanoTime();
        while (running) {
            long next = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (String service : services) {
                Map<String, Double> sample = new LinkedHashMap<>();
                for (Gauge gauge : GAUGES) {
                    ShopClient.Response response = client.admin(service, "GET", "/actuator/metrics/" + gauge.metric()
                            + (gauge.tag() == null ? "" : "?tag=" + gauge.tag()), null);
                    if (response.status() != 200) {
                        continue;
                    }
                    double value = 0;
                    for (var m : response.body().path("measurements")) {
                        value += m.path("value").asDouble();
                    }
                    if (gauge.label().endsWith("_mb")) {
                        value /= 1024 * 1024;
                    }
                    sample.put(gauge.label(), value);
                }
                if (sample.containsKey("process_cpu") && sample.containsKey("cpu_count")) {
                    sample.put("cpu_cores", sample.remove("process_cpu") * sample.remove("cpu_count"));
                }
                record(service, sample);
            }
            LockSupport.parkNanos(next - System.nanoTime());

            long[] machine = machineTicks();
            if (machine != null && machineBefore != null && machine[1] > machineBefore[1]) {
                double busy = (double) (machine[0] - machineBefore[0]) / (machine[1] - machineBefore[1]);
                record("machine", Map.of("cpu_cores", busy * machine[2], "cpu_share", busy));
            }
            machineBefore = machine;
            long generatorCpu = generatorCpuNanos();
            long wall = System.nanoTime();
            if (generatorCpu >= 0 && generatorCpuBefore >= 0) {
                record("generator", Map.of("cpu_cores", (double) (generatorCpu - generatorCpuBefore) / (wall - wallBefore)));
            }
            generatorCpuBefore = generatorCpu;
            wallBefore = wall;
        }
    }

    private void record(String source, Map<String, Double> sample) {
        synchronized (summaries) {
            Map<String, Summary> gauges = summaries.computeIfAbsent(source, s -> new LinkedHashMap<>());
            sample.forEach((label, value) -> gauges.computeIfAbsent(label, l -> new Summary()).add(value));
        }
    }

    /**
     * Busy and total jiffies of all CPUs since boot, and the number of CPUs, from /proc/stat (the whole host, even
     * when the generator runs under a quota); null where there is no /proc/stat.
     */
    private static long[] machineTicks() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/stat"));
            String[] fields = lines.getFirst().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < fields.length; i++) {
                total += Long.parseLong(fields[i]);
            }
            long idle = Long.parseLong(fields[4]) + Long.parseLong(fields[5]);
            long cpus = lines.stream().filter(l -> l.matches("cpu\\d+ .*")).count();
            return new long[] {total - idle, total, cpus};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long generatorCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : -1;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
