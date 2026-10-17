package io.github.dglearner.opsagent.shipper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Follows the log files in a directory and ships new lines to ingest-service (docs/shop-design.md §12.2), the way a
 * log collector agent would.
 *
 * <ul>
 * <li><b>Resuming:</b> the position in each file is saved after every accepted batch, so a restarted shipper goes on
 * where it stopped. A batch is sent with an Idempotency-Key made of the file's identity and the batch's start
 * position, the same on every attempt and after a restart; ingest derives the lines' event ids from it, and
 * stream-processor counts a line delivered twice only once.</li>
 * <li><b>Retries:</b> on 429, 5xx or no answer the same batch is sent again, after Retry-After or with backoff up to
 * 30 s, and the file waits. A batch refused for good (another 4xx) is skipped and reported.</li>
 * <li><b>Rotation:</b> a file replaced by a new one, or truncated, is read again from its start (see
 * {@link FileTail}). Lines written to the old file after the last read are lost, at most one interval's worth:
 * rotated files are compressed and not read.</li>
 * <li><b>Filtering:</b> lines of the {@code --drop-logger} loggers are dropped, by default {@code shop.faults}, the
 * ground truth of fault injection.</li>
 * </ul>
 */
public final class LogShipper {

    /** Ingest accepts request bodies of up to 1 MB. */
    private static final long MAX_BATCH_BYTES = 900 * 1024;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration SUMMARY_EVERY = Duration.ofSeconds(30);

    private final ShipperOptions options;
    private final IngestClient client;
    private final ShipperState state;
    private final List<String> dropMarkers;
    private final Map<String, FileTail> tails = new LinkedHashMap<>();
    private final Object sleeper = new Object();
    private volatile boolean running = true;
    private long shipped;
    private long dropped;
    private long retries;
    private long rejected;
    private Instant lastSummary = Instant.now();

    LogShipper(ShipperOptions options) {
        this.options = options;
        this.client = new IngestClient(options);
        this.state = new ShipperState(options.state());
        this.dropMarkers = options.dropLoggers().stream().map(l -> "\"logger\":\"" + l + "\"").toList();
    }

    public static void main(String[] args) throws Exception {
        ShipperOptions options;
        try {
            options = ShipperOptions.parse(args);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().isEmpty()) {
                System.err.println(e.getMessage());
            }
            System.err.println(ShipperOptions.USAGE);
            System.exit(e.getMessage().isEmpty() ? 0 : 2);
            return;
        }
        LogShipper shipper = new LogShipper(options);
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shipper.stop();
            try {
                main.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "log-shipper-stop"));
        System.out.printf("shipping %s/%s to %s as host %s, dropping loggers %s%n", options.dir(), options.glob(),
                options.url(), options.host(), options.dropLoggers());
        shipper.run();
    }

    /** Ships until stopped, or with {@code --once} until every file has been read to its end. */
    void run() throws IOException {
        start();
        while (running) {
            boolean shippedSomething = pollOnce();
            summaryIfDue(false);
            if (!shippedSomething) {
                if (options.once()) {
                    break;
                }
                pause(options.interval());
            }
        }
        summaryIfDue(true);
    }

    void stop() {
        running = false;
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    /** Positions for the files there at startup: saved ones, else the start or end of the file. */
    private void start() throws IOException {
        Map<String, ShipperState.Position> saved = state.load();
        for (Path file : files()) {
            String name = file.getFileName().toString();
            BasicFileAttributes attributes = FileTail.attributes(file);
            ShipperState.Position position = saved.get(name);
            tails.put(name, position != null
                    ? new FileTail(file, position.identity(), position.generation(), position.offset())
                    : new FileTail(file, FileTail.identity(attributes), 0,
                            options.fromStart() ? 0 : attributes.size()));
        }
        // Saved right away, so that starting from the end is remembered even if nothing is shipped before a restart.
        saveState();
    }

    /** One pass over the files; true if anything was read. */
    boolean pollOnce() throws IOException {
        boolean readSomething = false;
        for (Path file : files()) {
            if (!running) {
                break;
            }
            String name = file.getFileName().toString();
            BasicFileAttributes attributes = FileTail.attributes(file);
            // A file that appears while running is new, so all of it is shipped.
            FileTail tail = tails.computeIfAbsent(name, n -> new FileTail(file, FileTail.identity(attributes), 0, 0));
            if (tail.checkReplaced(attributes)) {
                System.out.printf("%s was rotated or truncated; reading it from the start%n", name);
            }
            if (attributes.size() <= tail.offset) {
                continue;
            }
            List<FileTail.Line> lines = tail.read(options.batchLines(), MAX_BATCH_BYTES);
            if (lines.isEmpty()) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (FileTail.Line line : lines) {
                if (dropMarkers.stream().anyMatch(line.text()::contains)) {
                    dropped++;
                } else {
                    kept.add(line.text());
                }
            }
            if (!kept.isEmpty() && !deliver(name, kept, idempotencyKey(name, tail))) {
                break;
            }
            tail.offset = lines.getLast().end();
            saveState();
            readSomething = true;
        }
        return readSomething;
    }

    /** Sends one batch until it is accepted or refused for good; false if the shipper was stopped meanwhile. */
    private boolean deliver(String fileName, List<String> lines, String idempotencyKey) {
        String service = fileName.endsWith(".log") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String sourceName = options.dir().resolve(fileName).toString().replace('\\', '/');
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            switch (client.send(service, sourceName, lines, idempotencyKey)) {
                case IngestClient.Result.Accepted accepted -> {
                    shipped += lines.size();
                    return true;
                }
                case IngestClient.Result.Rejected refusal -> {
                    rejected += lines.size();
                    System.out.printf("%s: %d lines refused, skipped: %s%n", fileName, lines.size(), refusal.reason());
                    return true;
                }
                case IngestClient.Result.Retry retry -> {
                    retries++;
                    Duration wait = retry.after() != null ? retry.after() : backoff;
                    System.out.printf("%s: %s; retrying in %d s%n", fileName, retry.reason(), wait.toSeconds());
                    pause(wait);
                    Duration doubled = backoff.multipliedBy(2);
                    backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
                }
            }
        }
        return false;
    }

    /**
     * The same for every attempt at the batch starting at the tail's position, also after a restart; different for
     * lines written after a rotation or truncation, even at the same position of a file with the same identity.
     */
    private String idempotencyKey(String fileName, FileTail tail) {
        String source = options.host() + "|" + fileName + "|" + tail.identity + "|" + tail.generation + "|"
                + tail.offset;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return "ship-" + HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void saveState() throws IOException {
        Map<String, ShipperState.Position> positions = new LinkedHashMap<>();
        tails.forEach((name, tail) -> positions.put(name,
                new ShipperState.Position(tail.identity, tail.generation, tail.offset)));
        state.save(positions);
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(options.dir())) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(options.dir(), options.glob())) {
            stream.forEach(p -> {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            });
        }
        files.sort(null);
        return files;
    }

    private void pause(Duration duration) {
        synchronized (sleeper) {
            if (running) {
                try {
                    sleeper.wait(Math.max(1, duration.toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private void summaryIfDue(boolean force) {
        if (force || Duration.between(lastSummary, Instant.now()).compareTo(SUMMARY_EVERY) >= 0) {
            System.out.printf("%s shipped %d lines (%d dropped, %d refused, %d retries) from %d files%n",
                    Instant.now(), shipped, dropped, rejected, retries, tails.size());
            lastSummary = Instant.now();
        }
    }

    long shipped() {
        return shipped;
    }

    long dropped() {
        return dropped;
    }
}
