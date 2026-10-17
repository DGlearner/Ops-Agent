package io.github.dglearner.opsagent.shipper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command-line options; {@link #USAGE} lists them. */
record ShipperOptions(Path dir, String glob, String url, String apiKey, Path state, String host,
        List<String> dropLoggers, int batchLines, Duration interval, boolean fromStart, boolean once) {

    static final String USAGE = """
            Usage: log-shipper [options]
              --dir DIR              directory of the log files (default logs/shop)
              --glob PATTERN         which files in it to ship (default *.log; rotated files are not read)
              --url URL              ingest-service (default http://localhost:8081)
              --key KEY              API key (default dev-ingest-key, accepted in ingest's local profile only)
              --state FILE           where reading positions are kept (default DIR/.log-shipper-state.json)
              --host NAME            host reported with each line (default this machine's name)
              --drop-logger NAME     drop lines of this logger; repeatable (default shop.faults: fault injection
                                     is the ground truth for evaluating Ops-Agent, which must not see it)
              --batch N              lines per request, at most 1000 (default 500)
              --interval D           how often to look for new lines, e.g. 1s, 500ms (default 1s)
              --from-start           ship files found at startup from their beginning (default: from their end;
                                     files that appear later, or after rotation, are always read from the start)
              --once                 ship what is there, then exit""";

    private static final Set<String> VALUE_OPTIONS = Set.of("--dir", "--glob", "--url", "--key", "--state", "--host",
            "--drop-logger", "--batch", "--interval");

    static ShipperOptions parse(String... args) {
        Path dir = Path.of("logs/shop");
        String glob = "*.log";
        String url = "http://localhost:8081";
        String apiKey = "dev-ingest-key";
        Path state = null;
        String host = null;
        List<String> dropLoggers = new ArrayList<>();
        int batchLines = 500;
        Duration interval = Duration.ofSeconds(1);
        boolean fromStart = false;
        boolean once = false;

        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            switch (name) {
                case "--help", "-h" -> throw new IllegalArgumentException("");
                case "--from-start" -> {
                    fromStart = true;
                    continue;
                }
                case "--once" -> {
                    once = true;
                    continue;
                }
                default -> {
                }
            }
            if (!VALUE_OPTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown option " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            String value = args[++i];
            switch (name) {
                case "--dir" -> dir = Path.of(value);
                case "--glob" -> glob = value;
                case "--url" -> url = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
                case "--key" -> apiKey = value;
                case "--state" -> state = Path.of(value);
                case "--host" -> host = value;
                case "--drop-logger" -> dropLoggers.add(value);
                case "--batch" -> {
                    batchLines = Integer.parseInt(value);
                    if (batchLines < 1 || batchLines > 1000) {
                        throw new IllegalArgumentException("--batch must be between 1 and 1000");
                    }
                }
                case "--interval" -> interval = duration(value);
                default -> throw new IllegalStateException("Unhandled option " + name);
            }
        }
        if (dropLoggers.isEmpty()) {
            dropLoggers.add("shop.faults");
        }
        Path stateFile = state != null ? state : dir.resolve(".log-shipper-state.json");
        return new ShipperOptions(dir, glob, url, apiKey, stateFile, host != null ? host : localHost(),
                List.copyOf(dropLoggers), batchLines, interval, fromStart, once);
    }

    static Duration duration(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("Not a duration: " + text + " (use e.g. 500ms or 2s)");
    }

    private static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
