package io.github.dglearner.shop.loadgen;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/** Command-line options; {@link #USAGE} lists them. */
record Options(String orderUrl, String inventoryUrl, String paymentUrl, double rate, Duration duration,
        int concurrency, int users, double duplicateRate, double declineRate, double hotRate, OptionalLong seed,
        Duration settleTimeout, boolean verify, Path report, String scenario, Duration baseline, Duration fault,
        Duration recovery, Map<String, String> adminUrls, String mysqlContainer, String flashSale, OptionalInt stock) {

    static final String USAGE = """
            Usage: load-generator [options]
              --order-url URL        order service (default http://localhost:8091)
              --inventory-url URL    inventory service, for the stock check (default http://localhost:8092)
              --payment-url URL      payment service, for the money check (default http://localhost:8093)
              --rate N               orders per second (default 5)
              --duration D           how long to send, e.g. 30s, 5m (default 30s)
              --concurrency N        most requests in flight at once (default 16)
              --users N              distinct user ids (default 50)
              --duplicate-rate F     share of orders sent again with the same Idempotency-Key (default 0.1)
              --decline-rate F       share of orders paid with tok_declined (default 0.05)
              --hot-rate F           share of orders that include the limited SKU-2001 (default 0.1)
              --seed N               random seed, for a repeatable run
              --settle-timeout D     how long to wait at the end for orders still in progress
                                     (default 10s, 90s with --scenario)
              --no-verify            skip the stock and money checks
              --report FILE          also write the summary as JSON
            Fault scenarios (instead of --duration: baseline, then the fault, then recovery):
              --scenario NAME        one of %s
              --baseline D           before the fault (default 20s)
              --fault D              how long the fault lasts (default 40s)
              --recovery D           after the fault (default 30s)
              --order-admin URL      management ports for fault injection
              --inventory-admin URL  (defaults http://localhost:8191, 8192, 8193)
              --payment-admin URL
              --mysql-container N    container stopped by mysql-down (default ops-shop-mysql-1)
            Flash sale (every order buys one unit of one SKU; --hot-rate does not apply):
              --flash-sale SKU       the SKU on sale, e.g. SKU-2001
              --stock N              units available when the sale starts, set through the inventory
                                     management port; refused while orders for the SKU are still in flight
            The checks assume nothing else orders or restocks while the run is going on.""".formatted(
            String.join(", ", Scenarios.names()));

    private static final Set<String> VALUE_OPTIONS = Set.of("--order-url", "--inventory-url", "--payment-url",
            "--rate", "--duration", "--concurrency", "--users", "--duplicate-rate", "--decline-rate", "--hot-rate",
            "--seed", "--settle-timeout", "--report", "--scenario", "--baseline", "--fault", "--recovery",
            "--order-admin", "--inventory-admin", "--payment-admin", "--mysql-container", "--flash-sale", "--stock");

    /** Whether this run injects a fault. */
    boolean hasScenario() {
        return scenario != null;
    }

    /** Whether every order buys one unit of {@link #flashSale()}. */
    boolean isFlashSale() {
        return flashSale != null;
    }

    static Options parse(String... args) {
        String orderUrl = "http://localhost:8091";
        String inventoryUrl = "http://localhost:8092";
        String paymentUrl = "http://localhost:8093";
        double rate = 5;
        Duration duration = Duration.ofSeconds(30);
        int concurrency = 16;
        int users = 50;
        double duplicateRate = 0.1;
        double declineRate = 0.05;
        double hotRate = 0.1;
        OptionalLong seed = OptionalLong.empty();
        Duration settleTimeout = null;
        boolean verify = true;
        Path report = null;
        String scenario = null;
        Duration baseline = Duration.ofSeconds(20);
        Duration fault = Duration.ofSeconds(40);
        Duration recovery = Duration.ofSeconds(30);
        Map<String, String> adminUrls = new LinkedHashMap<>(Map.of("order", "http://localhost:8191",
                "inventory", "http://localhost:8192", "payment", "http://localhost:8193"));
        String mysqlContainer = "ops-shop-mysql-1";
        String flashSale = null;
        OptionalInt stock = OptionalInt.empty();

        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.equals("--help") || name.equals("-h")) {
                throw new IllegalArgumentException("");
            }
            if (name.equals("--no-verify")) {
                verify = false;
                continue;
            }
            if (!VALUE_OPTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown option " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args[++i];
            switch (name) {
                case "--order-url" -> orderUrl = stripSlash(value);
                case "--inventory-url" -> inventoryUrl = stripSlash(value);
                case "--payment-url" -> paymentUrl = stripSlash(value);
                case "--rate" -> rate = positive(name, Double.parseDouble(value));
                case "--duration" -> duration = duration(value);
                case "--concurrency" -> concurrency = (int) positive(name, Integer.parseInt(value));
                case "--users" -> users = (int) positive(name, Integer.parseInt(value));
                case "--duplicate-rate" -> duplicateRate = share(name, value);
                case "--decline-rate" -> declineRate = share(name, value);
                case "--hot-rate" -> hotRate = share(name, value);
                case "--seed" -> seed = OptionalLong.of(Long.parseLong(value));
                case "--settle-timeout" -> settleTimeout = duration(value);
                case "--report" -> report = Path.of(value);
                case "--scenario" -> scenario = Scenarios.get(value).name();
                case "--baseline" -> baseline = duration(value);
                case "--fault" -> fault = duration(value);
                case "--recovery" -> recovery = duration(value);
                case "--order-admin" -> adminUrls.put("order", stripSlash(value));
                case "--inventory-admin" -> adminUrls.put("inventory", stripSlash(value));
                case "--payment-admin" -> adminUrls.put("payment", stripSlash(value));
                case "--mysql-container" -> mysqlContainer = value;
                case "--flash-sale" -> flashSale = value;
                case "--stock" -> stock = OptionalInt.of((int) nonNegative(name, Integer.parseInt(value)));
                default -> throw new IllegalStateException("Unhandled option " + name);
            }
        }
        if (stock.isPresent() && flashSale == null) {
            throw new IllegalArgumentException("--stock needs --flash-sale");
        }
        if (scenario != null) {
            duration = baseline.plus(fault).plus(recovery);
        }
        if (settleTimeout == null) {
            settleTimeout = Duration.ofSeconds(scenario == null ? 10 : 90);
        }
        return new Options(orderUrl, inventoryUrl, paymentUrl, rate, duration, concurrency, users, duplicateRate,
                declineRate, hotRate, seed, settleTimeout, verify, report, scenario, baseline, fault, recovery,
                Map.copyOf(adminUrls), mysqlContainer, flashSale, stock);
    }

    /** {@code 500ms}, {@code 30s}, {@code 5m} or {@code 1h}. */
    static Duration duration(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            return switch (value.charAt(value.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> throw new NumberFormatException();
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Not a duration: " + text + " (use e.g. 500ms, 30s, 5m)");
        }
    }

    private static double share(String name, String value) {
        double share = Double.parseDouble(value);
        if (share < 0 || share > 1) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
        return share;
    }

    private static double positive(String name, double value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static double nonNegative(String name, double value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
