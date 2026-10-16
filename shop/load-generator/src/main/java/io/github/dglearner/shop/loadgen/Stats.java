package io.github.dglearner.shop.loadgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters for one run. */
final class Stats {

    private static final int MAX_FAILURES_KEPT = 20;

    final Map<Integer, LongAdder> httpStatuses = new ConcurrentHashMap<>();
    final LongAdder orders = new LongAdder();
    final LongAdder skippedTicks = new LongAdder();
    final LongAdder duplicatesSent = new LongAdder();
    final LongAdder duplicatesSameOrder = new LongAdder();
    final LongAdder duplicatesInProgress = new LongAdder();
    final LongAdder duplicatesWrong = new LongAdder();
    final LongAdder unexpected = new LongAdder();
    final LongAdder retries = new LongAdder();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failuresSeen = new AtomicInteger();

    void response(ShopClient.Response response, boolean original) {
        httpStatuses.computeIfAbsent(response.status(), s -> new LongAdder()).increment();
        if (original) {
            latencies.add(response.latencyMillis());
        }
    }

    /** Remembers the first few failures with their trace ids, so they can be looked up in the logs. */
    void failure(String description) {
        if (failuresSeen.getAndIncrement() < MAX_FAILURES_KEPT) {
            failures.add(description);
        }
    }

    List<String> failures() {
        return List.copyOf(failures);
    }

    int failureCount() {
        return failuresSeen.get();
    }

    Map<Integer, Long> statusCounts() {
        Map<Integer, Long> counts = new TreeMap<>();
        httpStatuses.forEach((status, n) -> counts.put(status, n.sum()));
        return counts;
    }

    /** Nearest-rank percentile of original order requests, in milliseconds; 0 when nothing was sent. */
    long percentile(double p) {
        List<Long> sorted = new ArrayList<>(latencies);
        if (sorted.isEmpty()) {
            return 0;
        }
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
