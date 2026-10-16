package io.github.dglearner.shop.loadgen;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Places orders at a steady rate, repeats some of them with the same Idempotency-Key, optionally injects a fault
 * scenario meanwhile, then checks that every duplicate got the same order, that the stock ledger matches the orders
 * and that exactly the paid orders were charged (docs/shop-design.md §9–10).
 */
public final class LoadGenerator {

    /** What the run knows about one order; any response for it (original, duplicate, retry) can update the status. */
    private static final class Tracked {
        final List<OrderMix.Line> lines;
        volatile String status;
        volatile String reason;

        Tracked(List<OrderMix.Line> lines) {
            this.lines = lines;
        }

        boolean isFinal() {
            return "PAID".equals(status) || "CANCELLED".equals(status);
        }

        String outcome() {
            return reason == null || reason.isEmpty() ? String.valueOf(status) : status + "/" + reason;
        }
    }

    /** A request that failed before it could tell which order it made; retried with the same key, like a client. */
    private record Unanswered(String key, OrderMix.OrderRequest request) {
    }

    private final Options options;
    private final ShopClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Stats stats = new Stats();
    private final Map<String, Tracked> orders = new ConcurrentHashMap<>();
    private final Map<String, Unanswered> unanswered = new ConcurrentHashMap<>();
    /** Sends duplicates that go out alongside their original; separate because the main pool stops taking work. */
    private final ExecutorService duplicateSender = Executors.newVirtualThreadPerTaskExecutor();
    private final String runId;

    private LoadGenerator(Options options) {
        this.options = options;
        this.client = new ShopClient(options, json);
        this.runId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            boolean help = e.getMessage().isEmpty();
            if (!help) {
                System.err.println(e.getMessage());
            }
            System.err.println(Options.USAGE);
            System.exit(help ? 0 : 2);
            return;
        }
        System.exit(new LoadGenerator(options).run() ? 0 : 1);
    }

    private boolean run() throws Exception {
        List<String> skus = client.productSkus();
        Map<String, ShopClient.StockRow> before = options.verify() ? client.stock() : Map.of();
        long seed = options.seed().orElse(ThreadLocalRandom.current().nextLong());
        OrderMix mix = new OrderMix(skus, options, new Random(seed));
        System.out.printf("run %s: %.1f orders/s for %ds, seed %d%s%n", runId, options.rate(),
                options.duration().toSeconds(), seed, options.hasScenario() ? ", scenario " + options.scenario() : "");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.put("started_at", Instant.now().toString());
        report.put("seed", seed);
        report.put("rate", options.rate());
        ScenarioRunner scenario = options.hasScenario()
                ? new ScenarioRunner(Scenarios.get(options.scenario()), options, client) : null;
        // Ctrl+C or a kill ends the JVM without the finally block below; the hook still clears the fault.
        Thread clearOnExit = scenario == null ? null : new Thread(scenario::finish, "scenario-clear-on-exit");
        try {
            if (scenario != null) {
                Runtime.getRuntime().addShutdownHook(clearOnExit);
                scenario.start();
            }
            send(mix);
        } finally {
            if (scenario != null) {
                scenario.finish();
                removeShutdownHook(clearOnExit);
            }
            duplicateSender.close();
        }
        report.put("sending_ended_at", Instant.now().toString());
        if (scenario != null) {
            report.put("scenario", scenario.report());
        }

        long deadline = System.nanoTime() + options.settleTimeout().toNanos();
        settle(deadline);
        boolean ok = summarize(before, report, deadline) && (scenario == null || scenario.ok());
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAILED");
        report.put("ok", ok);
        if (options.report() != null) {
            if (options.report().getParent() != null) {
                Files.createDirectories(options.report().getParent());
            }
            Files.writeString(options.report(), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("report: " + options.report().toAbsolutePath());
        }
        return ok;
    }

    /** Ticks at the target rate; when {@code concurrency} requests are already in flight the tick is skipped. */
    private void send(OrderMix mix) {
        Semaphore inFlight = new Semaphore(options.concurrency());
        long interval = (long) (1_000_000_000L / options.rate());
        long start = System.nanoTime();
        long end = start + options.duration().toNanos();
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            int sequence = 0;
            for (long next = start; next < end; next += interval) {
                LockSupport.parkNanos(next - System.nanoTime());
                if (!inFlight.tryAcquire()) {
                    stats.skippedTicks.increment();
                    continue;
                }
                int n = sequence++;
                workers.submit(() -> {
                    try {
                        placeOne(mix, runId + "-" + n);
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
    }

    private void placeOne(OrderMix mix, String key) {
        OrderMix.OrderRequest request = mix.next();
        boolean duplicate = mix.duplicate();
        Future<ShopClient.Response> alongside = duplicate && mix.concurrentDuplicate()
                ? duplicateSender.submit(() -> client.placeOrder(key, request)) : null;

        ShopClient.Response original = client.placeOrder(key, request);
        stats.orders.increment();
        stats.response(original, true);
        track(original, request, key);
        if (!duplicate) {
            return;
        }
        ShopClient.Response repeat;
        try {
            repeat = alongside != null ? alongside.get() : client.placeOrder(key, request);
        } catch (Exception e) {
            stats.failure(key + ": duplicate request failed: " + e);
            return;
        }
        stats.duplicatesSent.increment();
        stats.response(repeat, false);
        track(repeat, request, key);
        checkDuplicate(key, original, repeat);
    }

    private void track(ShopClient.Response response, OrderMix.OrderRequest request, String key) {
        int status = response.status();
        String orderId = response.orderId();
        if (orderId.isEmpty()) {
            if (status >= 500) {
                // E.g. the database is down: no order may exist, or one may have been created before the failure.
                unanswered.putIfAbsent(key, new Unanswered(key, request));
                stats.failure(key + ": HTTP " + status + " " + response.body().path("code").asString()
                        + traceSuffix(response));
            } else {
                stats.unexpected.increment();
                stats.failure(key + ": " + (response.error() != null ? response.error() : "HTTP " + status + " "
                        + response.body().path("code").asString()) + traceSuffix(response));
            }
            return;
        }
        unanswered.remove(key);
        Tracked tracked = orders.computeIfAbsent(orderId, id -> new Tracked(request.lines()));
        String orderStatus = response.body().path("status").asString();
        if (!orderStatus.isEmpty()) {
            tracked.status = orderStatus;
            tracked.reason = response.body().path("failure_reason").asString();
        }
        if (status == 503) {
            stats.failure(key + ": 503 " + response.body().path("code").asString() + " order " + orderId
                    + traceSuffix(response));
        }
    }

    /** Both responses must refer to the same order; a 409 means the other request was still being processed. */
    private void checkDuplicate(String key, ShopClient.Response original, ShopClient.Response repeat) {
        String a = original.orderId();
        String b = repeat.orderId();
        if (a.isEmpty() && b.isEmpty() && original.status() >= 500 && repeat.status() >= 500) {
            return; // Both failed without an order (e.g. database down); the retry in settle() finds out.
        }
        if (a.isEmpty() || b.isEmpty() || !a.equals(b)) {
            if (a.isEmpty() || b.isEmpty()) {
                return; // One of them failed with a 5xx and is retried with the same key later.
            }
            stats.duplicatesWrong.increment();
            stats.failure(key + ": duplicate answered with order " + b + " instead of " + a + traceSuffix(repeat));
        } else if (original.status() == 409 || repeat.status() == 409) {
            stats.duplicatesInProgress.increment();
        } else {
            stats.duplicatesSameOrder.increment();
        }
    }

    /**
     * After sending: retries requests that never learned their order (same key, so no second order is created), and
     * waits for unfinished orders, e.g. those awaiting payment reconciliation.
     */
    private void settle(long deadline) {
        while (System.nanoTime() < deadline) {
            for (Unanswered u : List.copyOf(unanswered.values())) {
                ShopClient.Response response = client.placeOrder(u.key(), u.request());
                stats.retries.increment();
                if (!response.orderId().isEmpty()) {
                    track(response, u.request(), u.key());
                }
            }
            List<Map.Entry<String, Tracked>> open = orders.entrySet().stream()
                    .filter(e -> !e.getValue().isFinal()).toList();
            if (open.isEmpty() && unanswered.isEmpty()) {
                return;
            }
            for (Map.Entry<String, Tracked> entry : open) {
                ShopClient.Response response = client.order(entry.getKey());
                if (response.status() == 200) {
                    entry.getValue().status = response.body().path("status").asString();
                    entry.getValue().reason = response.body().path("failure_reason").asString();
                }
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        }
    }

    private boolean summarize(Map<String, ShopClient.StockRow> before, Map<String, Object> report, long deadline) {
        Map<String, Long> outcomes = orders.values().stream()
                .collect(Collectors.groupingBy(Tracked::outcome, TreeMap::new, Collectors.counting()));
        long unresolved = orders.values().stream().filter(t -> !t.isFinal()).count();

        System.out.printf("orders: %d sent (%d ticks skipped at the concurrency limit), %d duplicates, "
                        + "%d retries of unanswered requests%n", stats.orders.sum(), stats.skippedTicks.sum(),
                stats.duplicatesSent.sum(), stats.retries.sum());
        System.out.println("http: " + stats.statusCounts().entrySet().stream()
                .map(e -> (e.getKey() == 0 ? "network-error" : e.getKey()) + "x" + e.getValue())
                .collect(Collectors.joining(" ")));
        System.out.println("outcomes: " + outcomes.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        System.out.printf("latency: p50 %d ms, p95 %d ms, p99 %d ms%n",
                stats.percentile(50), stats.percentile(95), stats.percentile(99));
        System.out.printf("duplicates: %d same order, %d in progress, %d wrong%n", stats.duplicatesSameOrder.sum(),
                stats.duplicatesInProgress.sum(), stats.duplicatesWrong.sum());
        if (unresolved > 0 || !unanswered.isEmpty()) {
            System.out.printf("unresolved: %d orders not final, %d requests never answered with an order%n",
                    unresolved, unanswered.size());
        }

        boolean ok = stats.duplicatesWrong.sum() == 0 && stats.unexpected.sum() == 0 && unresolved == 0
                && unanswered.isEmpty();
        if (options.verify()) {
            ok &= checkLedger(before, report, deadline);
            ok &= checkMoney(report);
        }
        if (stats.failureCount() > 0) {
            System.out.printf("failures (%d, first %d shown; look the trace ids up in logs/shop):%n",
                    stats.failureCount(), stats.failures().size());
            stats.failures().forEach(f -> System.out.println("  " + f));
        }

        report.put("orders", stats.orders.sum());
        report.put("skipped_ticks", stats.skippedTicks.sum());
        report.put("retries", stats.retries.sum());
        report.put("http_statuses", stats.statusCounts());
        report.put("outcomes", outcomes);
        report.put("unresolved_orders", unresolved);
        report.put("unanswered_requests", unanswered.size());
        report.put("latency_ms", Map.of("p50", stats.percentile(50), "p95", stats.percentile(95),
                "p99", stats.percentile(99)));
        report.put("duplicates", Map.of("sent", stats.duplicatesSent.sum(), "same_order",
                stats.duplicatesSameOrder.sum(), "in_progress", stats.duplicatesInProgress.sum(),
                "wrong", stats.duplicatesWrong.sum()));
        report.put("failures", stats.failures());
        return ok;
    }

    /**
     * The ledger can lag the orders by a retry or two (a release still pending after a fault), so it is re-read
     * until it matches or the settle deadline passes.
     */
    private boolean checkLedger(Map<String, ShopClient.StockRow> before, Map<String, Object> report, long deadline) {
        LedgerCheck.Result check;
        while (true) {
            check = LedgerCheck.check(before, client.stock(), units(true), units(false));
            if (check.ok() || System.nanoTime() > deadline) {
                break;
            }
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
        }
        System.out.printf("stock ledger: %s (%d units sold, %d still reserved)%n", check.ok() ? "OK" : "MISMATCH",
                check.unitsSold(), check.unitsStillReserved());
        check.problems().forEach(p -> System.out.println("  " + p));
        report.put("ledger", Map.of("ok", check.ok(), "units_sold", check.unitsSold(), "problems", check.problems()));
        return check.ok();
    }

    /** Every paid order was charged, and no cancelled order was. */
    private boolean checkMoney(Map<String, Object> report) {
        Map<String, Long> byPaymentStatus = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        try (ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<ShopClient.Response>> payments = new HashMap<>();
            orders.keySet().forEach(id -> payments.put(id, lookups.submit(() -> client.payment(id))));
            for (Map.Entry<String, Future<ShopClient.Response>> entry : payments.entrySet()) {
                ShopClient.Response response = entry.getValue().get();
                String payment = response.status() == 404 ? "NONE"
                        : response.status() == 200 ? response.body().path("status").asString() : "ERROR";
                Tracked order = orders.get(entry.getKey());
                byPaymentStatus.merge(order.status + "/" + payment, 1L, Long::sum);
                boolean charged = payment.equals("SUCCEEDED");
                if ("PAID".equals(order.status) != charged || payment.equals("ERROR")) {
                    problems.add(entry.getKey() + ": order " + order.outcome() + " but payment " + payment);
                }
            }
        } catch (Exception e) {
            problems.add("payment lookups failed: " + e);
        }
        System.out.printf("money: %s (order/payment: %s)%n", problems.isEmpty() ? "OK" : "MISMATCH",
                byPaymentStatus.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", ")));
        problems.stream().limit(10).forEach(p -> System.out.println("  " + p));
        report.put("money", Map.of("ok", problems.isEmpty(), "by_status", byPaymentStatus,
                "problems", problems.stream().limit(50).toList()));
        return problems.isEmpty();
    }

    /** Units per SKU in paid orders ({@code paid}) or in unresolved orders that may still hold stock. */
    private Map<String, Integer> units(boolean paid) {
        Map<String, Integer> units = new HashMap<>();
        for (Tracked t : orders.values()) {
            boolean counts = paid ? "PAID".equals(t.status) : !t.isFinal() && !"CREATED".equals(t.status);
            if (counts) {
                t.lines.forEach(l -> units.merge(l.sku(), l.quantity(), Integer::sum));
            }
        }
        return units;
    }

    private static String traceSuffix(ShopClient.Response response) {
        return response.traceId().isEmpty() ? "" : " (trace " + response.traceId() + ")";
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Already shutting down: the hook is running or has run.
        }
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}
