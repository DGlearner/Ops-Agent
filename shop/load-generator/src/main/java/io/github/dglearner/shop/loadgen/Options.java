package io.github.dglearner.shop.loadgen;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

/** Command-line options; {@link #USAGE} lists them. */
record Options(String orderUrl, String inventoryUrl, double rate, Duration duration, int concurrency, int users,
        double duplicateRate, double declineRate, double hotRate, OptionalLong seed, Duration settleTimeout,
        boolean verify, Path report) {

    static final String USAGE = """
            Usage: load-generator [options]
              --order-url URL        order service (default http://localhost:8091)
              --inventory-url URL    inventory service, for the stock check (default http://localhost:8092)
              --rate N               orders per second (default 5)
              --duration D           how long to send, e.g. 30s, 5m (default 30s)
              --concurrency N        most requests in flight at once (default 16)
              --users N              distinct user ids (default 50)
              --duplicate-rate F     share of orders sent again with the same Idempotency-Key (default 0.1)
              --decline-rate F       share of orders paid with tok_declined (default 0.05)
              --hot-rate F           share of orders that include the limited SKU-2001 (default 0.1)
              --seed N               random seed, for a repeatable run
              --settle-timeout D     how long to wait at the end for orders still in progress (default 10s)
              --no-verify            skip the stock ledger check
              --report FILE          also write the summary as JSON
            The stock check assumes nothing else orders or restocks while the run is going on.""";

    private static final Set<String> VALUE_OPTIONS = Set.of("--order-url", "--inventory-url", "--rate", "--duration",
            "--concurrency", "--users", "--duplicate-rate", "--decline-rate", "--hot-rate", "--seed",
            "--settle-timeout", "--report");

    static Options parse(String... args) {
        String orderUrl = "http://localhost:8091";
        String inventoryUrl = "http://localhost:8092";
        double rate = 5;
        Duration duration = Duration.ofSeconds(30);
        int concurrency = 16;
        int users = 50;
        double duplicateRate = 0.1;
        double declineRate = 0.05;
        double hotRate = 0.1;
        OptionalLong seed = OptionalLong.empty();
        Duration settleTimeout = Duration.ofSeconds(10);
        boolean verify = true;
        Path report = null;

        for (int i = 0; i < args.length; i++) {
            String name = args[i];
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
                default -> throw new IllegalStateException("Unhandled option " + name);
            }
        }
        return new Options(orderUrl, inventoryUrl, rate, duration, concurrency, users, duplicateRate, declineRate,
                hotRate, seed, settleTimeout, verify, report);
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

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
