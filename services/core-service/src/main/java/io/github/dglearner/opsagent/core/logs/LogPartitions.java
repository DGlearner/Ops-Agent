package io.github.dglearner.opsagent.core.logs;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily partitions of {@code log_events} (UTC days of arrival): every day within the retention and two days ahead
 * exist, days past it are dropped whole, which costs nothing compared with deleting rows. Runs hourly and whenever a
 * line finds no partition. Under an advisory lock, so that several instances do not race on the same DDL.
 */
@Component
public class LogPartitions {

    private static final Logger log = LoggerFactory.getLogger(LogPartitions.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long LOCK = 0x6c6f6773L; // "logs"

    private final JdbcClient jdbc;
    private final LogProperties properties;
    private final Clock clock;

    LogPartitions(JdbcClient jdbc, LogProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void maintain() {
        ensure();
    }

    /** Creates the missing days and drops the expired ones; the names of the dropped partitions. */
    @Transactional
    public List<String> ensure() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK).query().singleRow();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (LocalDate day = firstKept(); !day.isAfter(today.plusDays(2)); day = day.plusDays(1)) {
            jdbc.sql("CREATE TABLE IF NOT EXISTS " + name(day) + " PARTITION OF log_events FOR VALUES FROM ('"
                    + day + " 00:00:00+00') TO ('" + day.plusDays(1) + " 00:00:00+00')").update();
        }
        LocalDate keepFrom = firstKept();
        List<String> expired = jdbc.sql("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'log_events' AND c.relname < ?""").param(name(keepFrom))
                .query(String.class).list();
        for (String partition : expired) {
            jdbc.sql("DROP TABLE IF EXISTS " + partition).update();
        }
        if (!expired.isEmpty()) {
            log.atInfo().addKeyValue("partitions", expired).log("Dropped {} expired log partitions", expired.size());
        }
        return expired;
    }

    /** Whether a line that arrived then has a partition: within the retention, and not days ahead of the clock. */
    boolean covers(Instant receivedAt) {
        LocalDate day = LocalDate.ofInstant(receivedAt, ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return !day.isBefore(firstKept()) && !day.isAfter(today.plusDays(2));
    }

    /** A partition holds one day; it goes once all of that day is older than the retention. */
    private LocalDate firstKept() {
        LocalDate keepFrom = LocalDate.ofInstant(clock.instant().minus(properties.retention()), ZoneOffset.UTC);
        LocalDate yesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        return keepFrom.isBefore(yesterday) ? keepFrom : yesterday;
    }

    static String name(LocalDate day) {
        return "log_events_" + SUFFIX.format(day);
    }
}
