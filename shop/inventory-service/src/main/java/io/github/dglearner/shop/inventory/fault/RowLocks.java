package io.github.dglearner.shop.inventory.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hot-row fault: holds the row lock of one SKU's stock for a while, the way a long transaction or a stuck batch job
 * would. Reservations of that SKU wait on the lock and fail after innodb_lock_wait_timeout; the connections they
 * hold meanwhile can exhaust the pool for everyone else. Logged under {@code shop.faults} only.
 */
@Component
public class RowLocks {

    private static final Logger log = LoggerFactory.getLogger("shop.faults");
    private static final Duration MAX_HOLD = Duration.ofMinutes(5);

    public record Held(String sku, Instant until) {
    }

    private record Holder(Held held, Thread thread) {
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public RowLocks(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<Held> held() {
        return holders.values().stream().map(Holder::held).toList();
    }

    /** Takes the lock and returns once it is held; the lock is released after {@code duration} or by {@link #release}. */
    public Held lock(String sku, Duration duration) throws InterruptedException {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_HOLD) > 0) {
            throw new IllegalArgumentException("duration must be between 1ms and " + MAX_HOLD.toMinutes() + " minutes");
        }
        if (holders.containsKey(sku)) {
            throw new IllegalArgumentException("A lock on " + sku + " is already held");
        }
        CountDownLatch locked = new CountDownLatch(1);
        boolean[] found = new boolean[1];
        Held held = new Held(sku, Instant.now().plus(duration));
        Thread thread = Thread.ofPlatform().name("rowlock-" + sku).daemon().unstarted(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    found[0] = jdbc.sql("SELECT sku FROM stock WHERE sku = :sku FOR UPDATE").param("sku", sku)
                            .query(String.class).optional().isPresent();
                    locked.countDown();
                    if (found[0]) {
                        hold(duration);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Row lock on {} failed: {}", sku, e.getMessage());
            } finally {
                holders.remove(sku);
                locked.countDown();
                log.atInfo().addKeyValue("sku", sku).log("Row lock released");
            }
        });
        holders.put(sku, new Holder(held, thread));
        thread.start();
        if (!locked.await(10, TimeUnit.SECONDS) || !found[0]) {
            thread.interrupt();
            throw new IllegalArgumentException("Could not lock the stock row of " + sku);
        }
        log.atInfo().addKeyValue("sku", sku).addKeyValue("duration_ms", duration.toMillis()).log("Row lock held");
        return held;
    }

    public int release() {
        List<Holder> current = List.copyOf(holders.values());
        current.forEach(h -> h.thread().interrupt());
        return current.size();
    }

    private static void hold(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
