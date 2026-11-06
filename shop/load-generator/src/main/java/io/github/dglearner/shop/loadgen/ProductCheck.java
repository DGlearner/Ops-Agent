package io.github.dglearner.shop.loadgen;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a product page at a steady rate while, optionally, its price keeps changing, and reports how the cache did
 * (docs/shop-design.md §17.6): latency, where the reads were answered, how often MySQL was read, and stale reads.
 * A read is stale when it started after an update to version N had been answered, yet returned a version below N;
 * its staleness is how long after that update it started. At the end, after a pause longer than any delayed delete
 * or local copy, every read must return the newest version: otherwise the cache was left stale.
 */
final class ProductCheck {

    /** order-service counters (§17.5) compared before and after the run: name, tag, value. */
    private static final List<String[]> COUNTERS = List.of(
            new String[] {"shop.product.reads", "served_by", "local"},
            new String[] {"shop.product.reads", "served_by", "redis"},
            new String[] {"shop.product.reads", "served_by", "database"},
            new String[] {"shop.product.reads", "served_by", "bloom"},
            new String[] {"shop.product.database.reads", "reason", "rebuild"},
            new String[] {"shop.product.database.reads", "reason", "lock_wait_timeout"},
            new String[] {"shop.product.database.reads", "reason", "redis_down"},
            new String[] {"shop.product.database.reads", "reason", "refused"},
            new String[] {"shop.product.database.reads", "reason", "cache_off"},
            new String[] {"shop.product.cache.lock", "result", "acquired"},
            new String[] {"shop.product.cache.lock", "result", "waited"},
            new String[] {"shop.product.cache.lock", "result", "wait_timeout"},
            new String[] {"shop.product.cache.writes", "result", "refused_older"},
            new String[] {"shop.product.cache.invalidations", "result", "retry"});

    private final Options options;
    private final ShopClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    /** The newest version whose update was answered, and when each version's update was answered (nanoTime). */
    private final AtomicLong confirmed = new AtomicLong();
    private final Map<Long, Long> confirmedAt = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> staleness = new ConcurrentLinkedQueue<>();
    private final LongAdder reads = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder skipped = new LongAdder();
    private final LongAdder updates = new LongAdder();
    private final LongAdder updateFailures = new LongAdder();
    private final String runId = Long.toHexString(System.currentTimeMillis());

    ProductCheck(Options options) {
        this.options = options;
        this.client = new ShopClient(options, json);
    }

    boolean run() throws Exception {
        String sku = options.product();
        ShopClient.Response first = client.product(sku);
        if (first.status() != 200) {
            System.out.println("cannot read product " + sku + ": HTTP " + first.status());
            return false;
        }
        long base = first.body().path("price_cents").asLong();
        confirmed.set(first.body().path("version").asLong());
        System.out.printf("product check %s: %s at %.1f reads/s for %ds%s%s%n", runId, sku, options.rate(),
                options.duration().toSeconds(),
                options.updateEvery() == null ? ""
                        : ", price changed every " + options.updateEvery().toMillis() + " ms",
                options.unknownRate() > 0 ? ", " + Math.round(options.unknownRate() * 100) + "% unknown products" : "");

        Map<String, Double> before = counters();
        ResourceSampler sampler = new ResourceSampler(client, List.of("order", "inventory", "payment"));
        sampler.start();
        long start = System.nanoTime();
        long end = start + options.duration().toNanos();
        Thread writer = options.updateEvery() == null ? null
                : Thread.ofPlatform().name("price-writer").start(() -> write(sku, base, end));
        read(sku, start, end);
        if (writer != null) {
            writer.join();
        }
        Map<String, Object> resources = sampler.stop();
        double seconds = (System.nanoTime() - start) / 1e9;

        // Longer than the delayed delete and than a local copy lives, so only a copy that stays stale is left.
        Thread.sleep(6000);
        long newest = confirmed.get();
        List<Long> finalVersions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            finalVersions.add(client.product(sku).body().path("version").asLong());
        }
        boolean consistent = finalVersions.stream().allMatch(v -> v == newest);
        Map<String, Double> during = delta(before, counters());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.put("started_at", Instant.now().toString());
        report.put("environment", RunEnvironment.describe(options));
        report.put("config", Map.of("product", sku, "rate", options.rate(),
                "duration_s", options.duration().toSeconds(), "concurrency", options.concurrency(),
                "update_every_ms", options.updateEvery() == null ? 0 : options.updateEvery().toMillis(),
                "unknown_rate", options.unknownRate()));
        report.put("reads", reads.sum());
        report.put("actual_rate", Math.round(reads.sum() / seconds * 10) / 10.0);
        report.put("skipped_ticks", skipped.sum());
        report.put("latency_ms", percentiles());
        report.put("updates", updates.sum());
        report.put("update_failures", updateFailures.sum());
        report.put("stale_reads", stale.sum());
        report.put("staleness_ms", staleness());
        report.put("final_versions", finalVersions);
        report.put("newest_version", newest);
        report.put("consistent_at_end", consistent);
        report.put("order_service", during);
        report.put("resources", resources);

        System.out.printf("reads: %d (%.1f/s, %d ticks skipped)%n", reads.sum(), reads.sum() / seconds, skipped.sum());
        percentiles().forEach((outcome, l) -> System.out.printf("  %s: %s%n", outcome, l));
        if (writer != null) {
            System.out.printf("updates: %d answered, %d failed; stale reads: %d (%.3f%%), staleness %s%n",
                    updates.sum(), updateFailures.sum(), stale.sum(), 100.0 * stale.sum() / Math.max(1, reads.sum()),
                    staleness());
        }
        System.out.printf("at the end: newest version %d, reads returned %s -> %s%n", newest, finalVersions,
                consistent ? "CONSISTENT" : "STALE");
        System.out.println("order-service during the run: " + during);
        LoadGenerator.printResources(resources);
        if (options.report() != null) {
            if (options.report().getParent() != null) {
                Files.createDirectories(options.report().getParent());
            }
            Files.writeString(options.report(), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("report: " + options.report().toAbsolutePath());
        }
        boolean ok = consistent && updateFailures.sum() == 0;
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAILED");
        return ok;
    }

    /** Ticks at the target rate; when {@code concurrency} reads are already in flight the tick is skipped. */
    private void read(String sku, long start, long end) {
        Semaphore inFlight = new Semaphore(options.concurrency());
        long interval = (long) (1_000_000_000L / options.rate());
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            int sequence = 0;
            for (long next = start; next < end; next += interval) {
                LockSupport.parkNanos(next - System.nanoTime());
                if (!inFlight.tryAcquire()) {
                    skipped.increment();
                    continue;
                }
                int n = sequence++;
                workers.submit(() -> {
                    try {
                        readOne(sku, n);
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
    }

    private void readOne(String sku, int n) {
        boolean unknown = options.unknownRate() > 0 && ThreadLocalRandom.current().nextDouble() < options.unknownRate();
        long known = confirmed.get();
        long start = System.nanoTime();
        ShopClient.Response response = client.product(unknown ? "NO-SUCH-" + runId + "-" + n : sku);
        reads.increment();
        String outcome = (unknown ? "unknown " : "") + (response.status() == 0 ? "network-error" : response.status());
        latencies.computeIfAbsent(outcome, o -> new ConcurrentLinkedQueue<>()).add(response.latencyMillis());
        if (!unknown && response.status() == 200) {
            long version = response.body().path("version").asLong();
            if (version < known) {
                stale.increment();
                Long changedAt = confirmedAt.get(version + 1);
                if (changedAt != null) {
                    staleness.add(TimeUnit.NANOSECONDS.toMillis(start - changedAt));
                }
            }
        }
    }

    /** Changes the price until {@code end}, one update at a time, every {@code update-every}. */
    private void write(String sku, long base, long end) {
        long n = 0;
        while (System.nanoTime() < end) {
            ShopClient.Response response = client.updatePrice(sku, base + 1 + (n++ % 100));
            if (response.status() == 200) {
                long version = response.body().path("version").asLong();
                confirmedAt.putIfAbsent(version, System.nanoTime());
                confirmed.accumulateAndGet(version, Math::max);
                updates.increment();
            } else {
                updateFailures.increment();
            }
            LockSupport.parkNanos(options.updateEvery().toNanos());
        }
    }

    private Map<String, Double> counters() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String[] c : COUNTERS) {
            ShopClient.Response response = client.admin("order", "GET",
                    "/actuator/metrics/" + c[0] + "?tag=" + c[1] + ":" + c[2], null);
            values.put(c[0] + "{" + c[2] + "}", response.status() != 200 ? 0
                    : response.body().path("measurements").path(0).path("value").asDouble());
        }
        return values;
    }

    private static Map<String, Double> delta(Map<String, Double> before, Map<String, Double> after) {
        Map<String, Double> delta = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            double d = v - before.getOrDefault(k, 0.0);
            if (d != 0) {
                delta.put(k, d);
            }
        });
        return delta;
    }

    private Map<String, Map<String, Long>> percentiles() {
        Map<String, Map<String, Long>> result = new TreeMap<>();
        latencies.forEach((outcome, values) -> result.put(outcome, summary(values)));
        return result;
    }

    private Map<String, Long> staleness() {
        return summary(staleness);
    }

    private static Map<String, Long> summary(ConcurrentLinkedQueue<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        Map<String, Long> s = new LinkedHashMap<>();
        s.put("count", (long) sorted.size());
        if (!sorted.isEmpty()) {
            s.put("p50", rank(sorted, 50));
            s.put("p95", rank(sorted, 95));
            s.put("p99", rank(sorted, 99));
            s.put("max", sorted.getLast());
        }
        return s;
    }

    private static long rank(List<Long> sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
