package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.Ids;
import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * How well real log files fit the parsers (docs/project-design.md §8.9): every line through {@link ParserRegistry},
 * without Kafka. For each label it prints the share parsed, the event types, and the commonest kinds of line that were
 * not parsed or only got a catch-all type. Kinds of line have numbers, IPs and hex taken out, so no address is printed.
 *
 * <pre>
 * ./gradlew :services:stream-processor:parseFit --args="linux:auth:/var/log/auth.log* nginx:nginx:/var/log/nginx/*.log*"
 * </pre>
 *
 * Each argument is {@code SOURCE_TYPE:LABEL:PATH}; a {@code *} in the file name matches rotated files, {@code .gz}
 * included.
 */
public final class ParseFit {

    /** The receive time given to every line: a parsed event with this time had none of its own. */
    private static final Instant RECEIVED_AT = Instant.parse("2000-01-01T00:00:00Z");
    private static final int TOP = 12;

    private static final class Stats {
        long lines;
        long parsed;
        long withoutTime;
        final Map<String, Long> eventTypes = new HashMap<>();
        final Map<String, Long> unparsed = new HashMap<>();
        final Map<String, Long> catchAll = new HashMap<>();
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: ParseFit SOURCE_TYPE:LABEL:PATH...");
            System.exit(2);
        }
        ParserRegistry registry = ParserRegistry.withDefaultParsers();
        Map<String, Stats> byLabel = new LinkedHashMap<>();
        for (String spec : args) {
            String[] parts = spec.split(":", 3);
            SourceType type = SourceType.valueOf(parts[0].toUpperCase(Locale.ROOT));
            Stats stats = byLabel.computeIfAbsent(parts[1], label -> new Stats());
            for (Path file : files(parts[2])) {
                try (BufferedReader reader = reader(file)) {
                    reader.lines().filter(line -> !line.isBlank())
                            .forEach(line -> count(registry, type, file, line, stats));
                }
            }
        }
        byLabel.forEach(ParseFit::print);
    }

    private static void count(ParserRegistry registry, SourceType type, Path file, String line, Stats stats) {
        stats.lines++;
        RawLogRecord raw = new RawLogRecord(Ids.newId(), null, "parse-fit", type, "parse-fit", null,
                file.getFileName().toString(), line, RECEIVED_AT);
        if (registry.parse(raw) instanceof ParseOutcome.Parsed(NormalizedEvent event)) {
            stats.parsed++;
            if (RECEIVED_AT.equals(event.timestamp())) {
                stats.withoutTime++;
            }
            stats.eventTypes.merge(event.eventType() + " (" + event.severity() + ")", 1L, Long::sum);
            if (event.eventType().endsWith("_event") || event.eventType().equals("syslog")) {
                stats.catchAll.merge(event.eventType() + " | " + kind(line), 1L, Long::sum);
            }
        } else {
            stats.unparsed.merge(kind(line), 1L, Long::sum);
        }
    }

    private static void print(String label, Stats stats) {
        System.out.printf("%n=== %s: %d lines, parsed %d (%.1f%%), without a time of their own %d%n", label,
                stats.lines, stats.parsed, 100.0 * stats.parsed / Math.max(1, stats.lines), stats.withoutTime);
        top("event types", stats.eventTypes, Integer.MAX_VALUE);
        top("unparsed, by kind of line", stats.unparsed, TOP);
        top("only a catch-all type, by kind of line", stats.catchAll, TOP);
    }

    private static void top(String title, Map<String, Long> counts, int limit) {
        if (counts.isEmpty()) {
            return;
        }
        System.out.println("  " + title + ":");
        counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .forEach(e -> System.out.printf("    %9d  %s%n", e.getValue(), e.getKey()));
    }

    /** The line with its variable parts taken out, so lines of the same kind count together. */
    static String kind(String line) {
        String kind = line
                .replaceAll("^\\d{4}-\\d{2}-\\d{2}T\\S+\\s+", "<time> ")
                .replaceAll("^[A-Z][a-z]{2}\\s+\\d+\\s[\\d:]+\\s", "<time> ")
                .replaceAll("\\[\\d+]", "[pid]")
                .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<ip>")
                .replaceAll("\\b[0-9a-fA-F:]{6,}\\b", "<hex>")
                .replaceAll("\\d+", "#");
        if (kind.startsWith("{")) {
            int action = kind.indexOf("\"Action\":\"");
            int end = action < 0 ? -1 : kind.indexOf('"', action + 10);
            return action < 0 ? "{json}" : "{json} " + kind.substring(action, end + 1);
        }
        return kind.length() > 120 ? kind.substring(0, 120) : kind;
    }

    private static List<Path> files(String pattern) throws IOException {
        Path path = Path.of(pattern);
        String name = path.getFileName().toString();
        if (!name.contains("*")) {
            return List.of(path);
        }
        Path dir = path.getParent() == null ? Path.of(".") : path.getParent();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(dir, name)) {
            matches.forEach(files::add);
        }
        files.sort(null);
        return files;
    }

    private static BufferedReader reader(Path file) {
        try {
            InputStream in = Files.newInputStream(file);
            if (file.getFileName().toString().endsWith(".gz")) {
                in = new GZIPInputStream(in);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(file.toString(), e);
        }
    }

    private ParseFit() {
    }
}
