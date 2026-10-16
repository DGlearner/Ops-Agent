package io.github.dglearner.shop.order;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backlog gauges (docs/shop-design.md §8.3): orders still in an intermediate state and inventory tasks not done, each
 * with the age of the oldest in seconds. They show what the counters cannot: recovery that does not converge.
 *
 * <p>Read from the database every few seconds, not at each scrape, so a scrape never waits on MySQL. While the
 * database cannot be read the gauges report NaN rather than stale values.
 */
@Component
public class BacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private static final List<String> UNRESOLVED = List.of("CREATED", "STOCK_RESERVED", "PAYMENT_UNKNOWN");
    private static final List<String> TASKS = List.of("PENDING", "FAILED");

    /** A gauge's current value. */
    private static final class Value {
        volatile double value = Double.NaN;
    }

    private final JdbcClient jdbc;
    private final Map<String, Value> orderCounts = new HashMap<>();
    private final Map<String, Value> orderAges = new HashMap<>();
    private final Map<String, Value> taskCounts = new HashMap<>();
    private final Value oldestPendingTask = new Value();

    public BacklogMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String status : UNRESOLVED) {
            String tag = status.toLowerCase(Locale.ROOT);
            gauge("shop.orders.unresolved", null, "orders in this intermediate state", orderCounts, status)
                    .tag("status", tag).register(registry);
            gauge("shop.orders.unresolved.oldest.age", "seconds",
                    "time since the oldest order in this state was placed", orderAges, status)
                    .tag("status", tag).register(registry);
        }
        gauge("shop.inventory.tasks.pending", null, "inventory tasks not yet done", taskCounts, "PENDING")
                .register(registry);
        gauge("shop.inventory.tasks.failed", null, "inventory tasks given up, to be handled by hand",
                taskCounts, "FAILED").register(registry);
        Gauge.builder("shop.inventory.tasks.oldest.age", oldestPendingTask, v -> v.value).baseUnit("seconds")
                .description("time since the oldest pending inventory task was written").register(registry);
    }

    private static Gauge.Builder<Value> gauge(String name, String unit, String description, Map<String, Value> values,
            String key) {
        Gauge.Builder<Value> builder = Gauge.builder(name, values.computeIfAbsent(key, k -> new Value()), v -> v.value)
                .description(description);
        return unit == null ? builder : builder.baseUnit(unit);
    }

    @Scheduled(fixedDelay = 5, initialDelay = 0, timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        try {
            Map<String, double[]> orders = countAndOldest("orders", UNRESOLVED);
            Map<String, double[]> tasks = countAndOldest("inventory_task", TASKS);
            UNRESOLVED.forEach(s -> {
                orderCounts.get(s).value = orders.getOrDefault(s, new double[2])[0];
                orderAges.get(s).value = orders.getOrDefault(s, new double[2])[1];
            });
            TASKS.forEach(s -> taskCounts.get(s).value = tasks.getOrDefault(s, new double[2])[0]);
            oldestPendingTask.value = tasks.getOrDefault("PENDING", new double[2])[1];
        } catch (DataAccessException e) {
            log.debug("Cannot read the backlog: {}", e.getMessage());
            orderCounts.values().forEach(v -> v.value = Double.NaN);
            orderAges.values().forEach(v -> v.value = Double.NaN);
            taskCounts.values().forEach(v -> v.value = Double.NaN);
            oldestPendingTask.value = Double.NaN;
        }
    }

    /** Per status: how many rows, and seconds since the oldest was created (measured by MySQL's clock). */
    private Map<String, double[]> countAndOldest(String table, List<String> statuses) {
        Map<String, double[]> result = new HashMap<>();
        jdbc.sql("SELECT status, COUNT(*) AS n, TIMESTAMPDIFF(MICROSECOND, MIN(created_at), NOW(3)) AS age_us FROM "
                        + table + " WHERE status IN (:statuses) GROUP BY status")
                .param("statuses", statuses)
                .query(rs -> {
                    result.put(rs.getString("status"),
                            new double[] {rs.getLong("n"), Math.max(0, rs.getLong("age_us")) / 1_000_000.0});
                });
        return result;
    }
}
