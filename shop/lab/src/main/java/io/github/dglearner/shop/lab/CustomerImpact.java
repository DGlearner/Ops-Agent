package io.github.dglearner.shop.lab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.stereotype.Component;

/**
 * Orders of the lab's traffic that failed for the customer: the shop could not take or settle them (a dependency
 * unavailable, the payment outcome unknown, a 5xx, no answer). Selling out and declined cards are not failures. Kept
 * per second in {@code customer-impact.txt} next to the ground truth, so an alert outside every injected fault can be
 * told apart: real trouble the customers saw, or a false alarm.
 */
@Component
class CustomerImpact {

    private final Path file;
    private final NavigableMap<Long, Integer> failuresPerSecond = new ConcurrentSkipListMap<>();

    CustomerImpact(LabProperties properties) {
        this.file = properties.groundTruth().resolveSibling("customer-impact.txt");
        load();
    }

    static boolean isFailure(String outcome) {
        return outcome.equals("NETWORK_ERROR") || outcome.equals("PAYMENT_UNKNOWN") || outcome.endsWith("_UNAVAILABLE")
                || outcome.startsWith("HTTP_5");
    }

    void record(String outcome) {
        if (!isFailure(outcome)) {
            return;
        }
        long second = Instant.now().getEpochSecond();
        failuresPerSecond.merge(second, 1, Integer::sum);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, second + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /** Failed orders between the two times, both included. */
    int failuresBetween(Instant from, Instant to) {
        return failuresPerSecond.subMap(from.getEpochSecond(), true, to.getEpochSecond(), true).values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    failuresPerSecond.merge(Long.parseLong(line.trim()), 1, Integer::sum);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
