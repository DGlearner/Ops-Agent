package io.github.dglearner.opsagent.core.logs;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

/**
 * Stores every line of {@link Topics#LOGS_NORMALIZED}, a poll at a time. A line that cannot be read is counted and
 * skipped: unlike an alert, one lost line does not lose a finding, and it should not hold back the others. A failing
 * database fails the batch, which the error handler retries with backoff, holding the partitions until it is back
 * (the same as alerts). A line that finds no partition (the clock passed midnight before the hourly maintenance)
 * makes the partitions be created, and the batch is written again. Lines that arrived outside the retention are
 * counted and dropped.
 */
@Component
class LogConsumer {

    private static final Logger log = LoggerFactory.getLogger(LogConsumer.class);

    private final LogStore store;
    private final LogPartitions partitions;
    private final MeterRegistry registry;

    LogConsumer(LogStore store, LogPartitions partitions, MeterRegistry registry) {
        this.store = store;
        this.partitions = partitions;
        this.registry = registry;
    }

    @KafkaListener(id = "logs", topics = Topics.LOGS_NORMALIZED, batch = "true")
    void consume(List<ConsumerRecord<String, String>> records) {
        List<NormalizedEvent> events = new ArrayList<>(records.size());
        int unreadable = 0;
        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(OpsJson.fromJson(record.value(), NormalizedEvent.class));
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                unreadable++;
                if (unreadable == 1) {
                    log.atWarn().addKeyValue("offset", record.offset()).addKeyValue("partition", record.partition())
                            .log("Unreadable log line skipped: {}", e.getMessage());
                }
            }
        }
        if (unreadable > 0) {
            registry.counter("ops.core.logs", "result", "unreadable").increment(unreadable);
        }
        // Lines older than the retention, or stamped days ahead by a wrong clock, have no partition and never will:
        // left in, they would fail the batch for good.
        int before = events.size();
        events.removeIf(e -> !partitions.covers(arrival(e)));
        if (events.size() < before) {
            registry.counter("ops.core.logs", "result", "out_of_range").increment(before - events.size());
        }
        if (events.isEmpty()) {
            return;
        }
        try {
            store.insert(events);
        } catch (DataAccessException e) {
            if (!noPartition(e)) {
                throw e;
            }
            log.atInfo().log("A log line found no partition; creating them and writing the batch again");
            partitions.ensure();
            store.insert(events);
        }
        registry.counter("ops.core.logs", "result", "stored").increment(events.size());
    }

    static Instant arrival(NormalizedEvent e) {
        return e.receivedAt() != null ? e.receivedAt() : e.timestamp();
    }

    private static boolean noPartition(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLException sql && "23514".equals(sql.getSQLState())
                && String.valueOf(sql.getMessage()).contains("no partition");
    }
}
