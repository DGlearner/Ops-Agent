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
    /** Flash sale through Redis: buyers turned away with 409 SOLD_OUT, which creates no order. */
    final LongAdder soldOut = new LongAdder();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final Map<String, ConcurrentLinkedQueue<Long>> latenciesByOutcome = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failuresSeen = new AtomicInteger();

    void response(ShopClient.Response response, boolean original) {
        httpStatuses.computeIfAbsent(response.status(), s -> new LongAdder()).increment();
        if (original) {
            latencies.add(response.latencyMillis());
            latenciesByOutcome.computeIfAbsent(outcome(response), o -> new ConcurrentLinkedQueue<>())
                    .add(response.latencyMillis());
        }
    }

    /**
     * What a response said, for latency per outcome: an order's status and reason (e.g. {@code CANCELLED/OUT_OF_STOCK}),
     * {@code PAYMENT_UNKNOWN} for a 202, or the HTTP status and problem code.
     */
    static String outcome(ShopClient.Response response) {
        int status = response.status();
        String code = response.body().path("code").asString();
        if (status == 0) {
            return "NETWORK_ERROR";
        }
        if (status == 200 || status == 201) {
            String reason = response.body().path("failure_reason").asString();
            String orderStatus = response.body().path("status").asString();
            return reason.isEmpty() ? orderStatus : orderStatus + "/" + reason;
        }
        if (status == 202) {
            // An order awaiting payment reconciliation, or a flash sale order waiting in the queue (QUEUED).
            String orderStatus = response.body().path("status").asString();
            return orderStatus.isEmpty() ? "PAYMENT_UNKNOWN" : orderStatus;
        }
        return "HTTP_" + status + (code.isEmpty() ? "" : "/" + code);
    }

    /** Count and p50/p95/p99 in milliseconds of original requests, per outcome. */
    Map<String, Map<String, Long>> latencyByOutcome() {
        Map<String, Map<String, Long>> result = new TreeMap<>();
        latenciesByOutcome.forEach((outcome, values) -> {
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            result.put(outcome, Map.of("count", (long) sorted.size(), "p50", rank(sorted, 50), "p95", rank(sorted, 95),
                    "p99", rank(sorted, 99)));
        });
        return result;
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
        Collections.sort(sorted);
        return rank(sorted, p);
    }

    private static long rank(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
