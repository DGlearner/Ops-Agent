package io.github.dglearner.shop.lab;

import io.github.dglearner.shop.loadgen.OrderMix;
import io.github.dglearner.shop.loadgen.Options;
import io.github.dglearner.shop.loadgen.ShopClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orders sent in the background: steady normal traffic that runs until stopped, and flash sales that run for a set
 * time. Each is a run of its own; normal traffic is the baseline during which Ops-Agent should stay quiet.
 */
@Component
class Traffic {

    private static final int MAX_IN_FLIGHT = 64;
    private static final Duration RECENT = Duration.ofSeconds(60);

    /** One stream of orders at a rate, until stopped or until {@code until}. */
    final class Run {
        final String label;
        final double rate;
        final Instant startedAt = Instant.now();
        final Instant until;
        private final OrderMix mix;
        private final AtomicLong sent = new AtomicLong();
        private final ConcurrentLinkedDeque<Map.Entry<Instant, String>> recent = new ConcurrentLinkedDeque<>();
        private final Thread thread;
        private volatile boolean running = true;

        Run(String label, double rate, Instant until, OrderMix mix) {
            this.label = label;
            this.rate = rate;
            this.until = until;
            this.mix = mix;
            this.thread = Thread.ofPlatform().name("traffic-" + label).daemon().unstarted(this::send);
        }

        private void send() {
            Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);
            long interval = (long) (1_000_000_000L / rate);
            String prefix = "lab-" + UUID.randomUUID().toString().substring(0, 8) + "-";
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                long next = System.nanoTime();
                long n = 0;
                while (running && (until == null || Instant.now().isBefore(until))) {
                    LockSupport.parkNanos(next - System.nanoTime());
                    next += interval;
                    if (!inFlight.tryAcquire()) {
                        count("SKIPPED");
                        continue;
                    }
                    String key = prefix + n++;
                    workers.submit(() -> {
                        try {
                            placeOne(key);
                        } finally {
                            inFlight.release();
                        }
                    });
                }
            }
            running = false;
        }

        private void placeOne(String key) {
            OrderMix.OrderRequest request = mix.next();
            ShopClient.Response response = client.placeOrder(key, request);
            sent.incrementAndGet();
            String outcome = outcome(response);
            count(outcome);
            impact.record(outcome);
            if (mix.duplicate()) {
                client.placeOrder(key, request);
            }
        }

        private void count(String name) {
            Instant now = Instant.now();
            recent.add(Map.entry(now, name));
            while (!recent.isEmpty() && recent.peekFirst().getKey().isBefore(now.minus(RECENT))) {
                recent.pollFirst();
            }
        }

        boolean running() {
            return running;
        }

        void stop() {
            running = false;
            try {
                thread.join(TimeUnit.SECONDS.toMillis(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Map<String, Object> status() {
            Map<String, Long> lastMinute = new TreeMap<>();
            Instant since = Instant.now().minus(RECENT);
            recent.forEach(e -> {
                if (!e.getKey().isBefore(since)) {
                    lastMinute.merge(e.getValue(), 1L, Long::sum);
                }
            });
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("label", label);
            status.put("running", running);
            status.put("rate", rate);
            status.put("started_at", startedAt);
            status.put("until", until);
            status.put("sent", sent.get());
            status.put("last_minute", lastMinute);
            return status;
        }
    }

    private final LabProperties properties;
    private final CustomerImpact impact;
    private final ShopClient client;
    private Run normal;
    private final List<Run> sales = new ArrayList<>();

    Traffic(LabProperties properties, CustomerImpact impact, JsonMapper json) {
        this.properties = properties;
        this.impact = impact;
        this.client = new ShopClient(Options.parse(properties.generatorArgs().toArray(String[]::new)), json);
    }

    ShopClient client() {
        return client;
    }

    /** Starts steady traffic, replacing what is running. */
    synchronized void start(double rate, double duplicateRate, double declineRate) {
        stop();
        Options options = options("--rate", rate, "--duplicate-rate", duplicateRate, "--decline-rate", declineRate,
                "--hot-rate", 0.05);
        normal = new Run("normal", rate, null, new OrderMix(client.productSkus(), options, new Random()));
        normal.thread.start();
    }

    synchronized void stop() {
        if (normal != null) {
            normal.stop();
            normal = null;
        }
    }

    /** A flash sale of one SKU: every order buys one unit, at {@code rate} for {@code duration}. */
    synchronized Run flashSale(String sku, double rate, Duration duration, double declineRate) {
        Options options = options("--rate", rate, "--duplicate-rate", 0.05, "--decline-rate", declineRate,
                "--flash-sale", sku);
        Run sale = new Run("flash-sale " + sku, rate, Instant.now().plus(duration),
                new OrderMix(client.productSkus(), options, new Random()));
        sales.removeIf(r -> !r.running());
        sales.add(sale);
        sale.thread.start();
        return sale;
    }

    synchronized List<Map<String, Object>> status() {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (normal != null) {
            runs.add(normal.status());
        }
        sales.stream().filter(Run::running).forEach(r -> runs.add(r.status()));
        return runs;
    }

    private Options options(Object... settings) {
        List<String> args = new ArrayList<>(properties.generatorArgs());
        for (Object setting : settings) {
            args.add(String.valueOf(setting));
        }
        args.addAll(List.of("--users", "500"));
        return Options.parse(args.toArray(String[]::new));
    }

    /** What an answer said: the order's status and reason, or the HTTP status and problem code. */
    static String outcome(ShopClient.Response response) {
        int status = response.status();
        if (status == 0) {
            return "NETWORK_ERROR";
        }
        if (status == 200 || status == 201) {
            String reason = response.body().path("failure_reason").asString();
            String order = response.body().path("status").asString();
            return reason.isEmpty() ? order : order + "/" + reason;
        }
        if (status == 202) {
            return "PAYMENT_UNKNOWN";
        }
        String code = response.body().path("code").asString();
        return "HTTP_" + status + (code.isEmpty() ? "" : "/" + code);
    }
}
