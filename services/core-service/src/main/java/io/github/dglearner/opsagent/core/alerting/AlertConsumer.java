package io.github.dglearner.opsagent.core.alerting;

import io.github.dglearner.opsagent.contracts.Alert;
import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stores every alert from {@link Topics#ALERTS}. Delivery is at least once; storing is idempotent on the alert id, so
 * a redelivered or replayed alert is counted as a duplicate and changes nothing.
 */
@Component
class AlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertConsumer.class);

    private final Alerts alerts;
    private final MeterRegistry registry;

    AlertConsumer(Alerts alerts, MeterRegistry registry) {
        this.alerts = alerts;
        this.registry = registry;
    }

    @KafkaListener(id = "alerts", topics = Topics.ALERTS)
    void consume(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new IllegalArgumentException("Alert message without a value at offset " + record.offset());
        }
        Alert alert = OpsJson.fromJson(record.value(), Alert.class);
        boolean stored = alerts.store(alert);
        registry.counter("ops.core.alerts", "result", stored ? "stored" : "duplicate").increment();
        if (stored) {
            log.atInfo().addKeyValue("alert_id", alert.alertId()).addKeyValue("rule_id", alert.ruleId())
                    .addKeyValue("service", alert.service()).log("Alert stored: {}", alert.summary());
        }
    }
}
