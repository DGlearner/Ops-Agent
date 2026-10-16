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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Places orders at a steady rate, repeats some of them with the same Idempotency-Key, optionally injects a fault
 * scenario meanwhile, then checks that every duplicate got the same order, that the stock ledger matches the orders,
 * and, order by order, that exactly the paid orders were charged and had their stock confirmed (docs/shop-design.md
 * §7.6, §9–10).
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
    private volatile long sendingStartedAt;
    /** When the first order was answered out of stock, in {@link System#nanoTime()} terms. */
    private final AtomicLong firstOutOfStockAt = new AtomicLong(Long.MAX_VALUE);

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
        long seed = options.seed().orElse(ThreadLocalRandom.current().nextLong());
        OrderMix mix = new OrderMix(skus, options, new Random(seed));
        if (options.stock().isPresent() && !prepareStock(options.flashSale(), options.stock().getAsInt())) {
            return false;
        }
        Map<String, ShopClient.StockRow> before = options.verify() || options.isFlashSale() ? client.stock() : Map.of();
        System.out.printf("run %s: %.1f orders/s for %ds, seed %d%s%s%n", runId, options.rate(),
                options.duration().toSeconds(), seed, options.hasScenario() ? ", scenario " + options.scenario() : "",
                options.isFlashSale() ? ", flash sale of " + options.flashSale() : "");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.put("started_at", Instant.now().toString());
        report.put("seed", seed);
        report.put("rate", options.rate());
        report.put("environment", RunEnvironment.describe(options));
        report.put("config", options.describe());
        Map<String, Long> locksBefore = RunEnvironment.rowLocks(options.mysqlContainer());
        ResourceSampler sampler = new ResourceSampler(client, List.of("order", "inventory", "payment"));
        Map<String, Object> resources;
        ScenarioRunner scenario = options.hasScenario()
                ? new ScenarioRunner(Scenarios.get(options.scenario()), options, client) : null;
        // Ctrl+C or a kill ends the JVM without the finally block below; the hook still clears the fault.
        Thread clearOnExit = scenario == null ? null : new Thread(scenario::finish, "scenario-clear-on-exit");
        long sendingFrom = System.nanoTime();
        try {
            if (scenario != null) {
                Runtime.getRuntime().addShutdownHook(clearOnExit);
                scenario.start();
            }
            sampler.start();
            send(mix);
        } finally {
            resources = sampler.stop();
            if (scenario != null) {
                scenario.finish();
                removeShutdownHook(clearOnExit);
            }
            duplicateSender.close();
        }
        double sendingSeconds = (System.nanoTime() - sendingFrom) / 1e9;
        report.put("sending_ended_at", Instant.now().toString());
        report.put("sending_s", Math.round(sendingSeconds * 10) / 10.0);
        report.put("actual_rate", Math.round(stats.orders.sum() / sendingSeconds * 10) / 10.0);
        report.put("resources", resources);
        if (scenario != null) {
            report.put("scenario", scenario.report());
        }

        long deadline = System.nanoTime() + options.settleTimeout().toNanos();
        settle(deadline);
        boolean ok = summarize(before, report, deadline) && (scenario == null || scenario.ok());
        printResources(resources);
        report.put("mysql_row_locks", rowLocksDuring(locksBefore));
        report.put("backlog_at_end", backlogAtEnd());
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
        sendingStartedAt = start;
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
            if ("OUT_OF_STOCK".equals(tracked.reason)) {
                firstOutOfStockAt.accumulateAndGet(System.nanoTime(), Math::min);
            }
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
        System.out.printf("rate: %s orders/s sent, %.1f planned%n", report.get("actual_rate"), options.rate());
        System.out.println("http: " + stats.statusCounts().entrySet().stream()
                .map(e -> (e.getKey() == 0 ? "network-error" : e.getKey()) + "x" + e.getValue())
                .collect(Collectors.joining(" ")));
        System.out.println("outcomes: " + outcomes.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        System.out.printf("latency: p50 %d ms, p95 %d ms, p99 %d ms%n",
                stats.percentile(50), stats.percentile(95), stats.percentile(99));
        Map<String, Map<String, Long>> byOutcome = stats.latencyByOutcome();
        byOutcome.forEach((outcome, l) -> System.out.printf("  %s: %d, p50 %d ms, p95 %d ms, p99 %d ms%n", outcome,
                l.get("count"), l.get("p50"), l.get("p95"), l.get("p99")));
        System.out.printf("duplicates: %d same order, %d in progress, %d wrong%n", stats.duplicatesSameOrder.sum(),
                stats.duplicatesInProgress.sum(), stats.duplicatesWrong.sum());
        if (unresolved > 0 || !unanswered.isEmpty()) {
            System.out.printf("unresolved: %d orders not final, %d requests never answered with an order%n",
                    unresolved, unanswered.size());
        }

        if (options.isFlashSale()) {
            report.put("flash_sale", flashSale(before, outcomes));
        }

        boolean ok = stats.duplicatesWrong.sum() == 0 && stats.unexpected.sum() == 0 && unresolved == 0
                && unanswered.isEmpty();
        if (options.verify()) {
            ok &= checkLedger(before, report, deadline);
            ok &= checkOrders(report);
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
        report.put("latency_ms_by_outcome", byOutcome);
        report.put("requests", Map.of("original", stats.orders.sum(), "duplicate", stats.duplicatesSent.sum(),
                "retry", stats.retries.sum()));
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

    @SuppressWarnings("unchecked")
    private static void printResources(Map<String, Object> resources) {
        System.out.println("resources while sending (mean / max; cpu in cores):");
        resources.forEach((source, gauges) -> {
            Map<String, Map<String, Object>> g = (Map<String, Map<String, Object>>) gauges;
            if (g.isEmpty()) {
                System.out.println("  " + source + ": not reachable");
            } else if (!g.containsKey("db_connections_max")) {
                System.out.printf("  %-9s cpu %s / %s%n", source, v(g, "cpu_cores", "mean"), v(g, "cpu_cores", "max"));
            } else {
                System.out.printf("  %-9s cpu %s / %s, db connections %s / %s active of %s, %s / %s waiting, "
                                + "request threads %s / %s busy of %s, heap %s / %s MB%n", source,
                        v(g, "cpu_cores", "mean"), v(g, "cpu_cores", "max"), v(g, "db_connections_active", "mean"),
                        v(g, "db_connections_active", "max"), v(g, "db_connections_max", "max"),
                        v(g, "db_connections_pending", "mean"), v(g, "db_connections_pending", "max"),
                        v(g, "request_threads_busy", "mean"), v(g, "request_threads_busy", "max"),
                        v(g, "request_threads_max", "max"), v(g, "heap_used_mb", "mean"), v(g, "heap_used_mb", "max"));
            }
        });
    }

    private static Object v(Map<String, Map<String, Object>> gauges, String gauge, String statistic) {
        Map<String, Object> values = gauges.get(gauge);
        return values == null ? "-" : values.get(statistic);
    }

    /** MySQL row lock waits during the run (including anything else using that MySQL meanwhile). */
    private Map<String, Object> rowLocksDuring(Map<String, Long> before) {
        Map<String, Long> after = RunEnvironment.rowLocks(options.mysqlContainer());
        Map<String, Object> during = new LinkedHashMap<>();
        if (before.isEmpty() || after.isEmpty()) {
            System.out.println("mysql row locks: unknown (cannot read the counters of " + options.mysqlContainer() + ")");
            return during;
        }
        long waits = after.get("innodb_row_lock_waits") - before.get("innodb_row_lock_waits");
        long waitedMs = after.get("innodb_row_lock_time") - before.get("innodb_row_lock_time");
        System.out.printf("mysql row locks: %d waits, %d ms waited in all%s%n", waits, waitedMs,
                waits == 0 ? "" : ", " + waitedMs / waits + " ms on average");
        during.put("waits", waits);
        during.put("waited_ms", waitedMs);
        return during;
    }

    /** Inventory tasks still pending or given up, as order-service's backlog gauges see them (refreshed every 5 s). */
    private Map<String, Object> backlogAtEnd() {
        Map<String, Object> backlog = new LinkedHashMap<>();
        for (String gauge : List.of("shop.inventory.tasks.pending", "shop.inventory.tasks.failed")) {
            ShopClient.Response response = client.admin("order", "GET", "/actuator/metrics/" + gauge, null);
            backlog.put(gauge, response.status() == 200
                    ? response.body().path("measurements").path(0).path("value").asDouble() : null);
        }
        System.out.printf("backlog at the end: %s pending and %s failed inventory tasks%n",
                backlog.get("shop.inventory.tasks.pending"), backlog.get("shop.inventory.tasks.failed"));
        return backlog;
    }

    /** Sets how many units of the SKU on sale are available before the sale starts. */
    private boolean prepareStock(String sku, int units) {
        ShopClient.Response response = client.admin("inventory", "POST", "/actuator/stockprep",
                "{\"sku\":\"" + sku + "\",\"available\":" + units + "}");
        if (response.status() != 200) {
            System.out.println("cannot prepare the stock of " + sku + ": "
                    + (response.error() != null ? response.error() : "HTTP " + response.status() + " "
                    + response.body().path("error").asString()));
            return false;
        }
        return true;
    }

    /** The sale in numbers: stock at the start, units sold, buyers turned away, and how soon it sold out. */
    private Map<String, Object> flashSale(Map<String, ShopClient.StockRow> before, Map<String, Long> outcomes) {
        String sku = options.flashSale();
        int atStart = before.containsKey(sku) ? before.get(sku).available() : -1;
        ShopClient.StockRow after = client.stock().get(sku);
        long paid = outcomes.getOrDefault("PAID", 0L);
        long outOfStock = outcomes.getOrDefault("CANCELLED/OUT_OF_STOCK", 0L);
        long first = firstOutOfStockAt.get();
        Long soldOutAfter = first == Long.MAX_VALUE ? null : TimeUnit.NANOSECONDS.toMillis(first - sendingStartedAt);
        System.out.printf("flash sale: %s, %d available at the start, %d paid orders, %d answered out of stock%s, "
                        + "%d left%n", sku, atStart, paid, outOfStock,
                soldOutAfter == null ? "" : " (the first after " + soldOutAfter + " ms)", after.available());
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("sku", sku);
        sale.put("available_at_start", atStart);
        sale.put("paid_orders", paid);
        sale.put("out_of_stock", outOfStock);
        sale.put("first_out_of_stock_after_ms", soldOutAfter);
        sale.put("available_at_end", after.available());
        return sale;
    }

    /** Order by order: payment and reservation agree with how the order ended (see {@link OrderCheck}). */
    private boolean checkOrders(Map<String, Object> report) {
        Map<String, Long> byPayment = new TreeMap<>();
        Map<String, Long> byReservation = new TreeMap<>();
        List<String> paymentProblems = new ArrayList<>();
        List<String> stockProblems = new ArrayList<>();
        // At most 32 lookups at a time, so checking a big run does not flood the services.
        Semaphore inFlight = new Semaphore(32);
        try (ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<OrderCheck.Result>> results = new HashMap<>();
            orders.forEach((id, tracked) -> {
                OrderCheck.Order order = new OrderCheck.Order(id, tracked.status, tracked.outcome(), tracked.lines);
                results.put(id, lookups.submit(() -> {
                    inFlight.acquire();
                    try {
                        return OrderCheck.check(order, client.payment(id), client.reservation(id));
                    } finally {
                        inFlight.release();
                    }
                }));
            });
            for (Map.Entry<String, Future<OrderCheck.Result>> entry : results.entrySet()) {
                OrderCheck.Result result = entry.getValue().get();
                String status = orders.get(entry.getKey()).status;
                byPayment.merge(status + "/" + result.payment(), 1L, Long::sum);
                byReservation.merge(status + "/" + result.reservation(), 1L, Long::sum);
                paymentProblems.addAll(result.paymentProblems());
                stockProblems.addAll(result.stockProblems());
            }
        } catch (Exception e) {
            paymentProblems.add("payment or reservation lookups failed: " + e);
            stockProblems.add("payment or reservation lookups failed: " + e);
        }
        System.out.printf("money: %s (order/payment: %s)%n", paymentProblems.isEmpty() ? "OK" : "MISMATCH",
                joined(byPayment));
        paymentProblems.stream().limit(10).forEach(p -> System.out.println("  " + p));
        System.out.printf("reservations: %s (order/reservation: %s)%n", stockProblems.isEmpty() ? "OK" : "MISMATCH",
                joined(byReservation));
        stockProblems.stream().limit(10).forEach(p -> System.out.println("  " + p));
        report.put("money", Map.of("ok", paymentProblems.isEmpty(), "by_status", byPayment,
                "problems", paymentProblems.stream().limit(50).toList()));
        report.put("reservations", Map.of("ok", stockProblems.isEmpty(), "by_status", byReservation,
                "problems", stockProblems.stream().limit(50).toList()));
        return paymentProblems.isEmpty() && stockProblems.isEmpty();
    }

    private static String joined(Map<String, Long> counts) {
        return counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
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
