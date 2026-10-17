package io.github.dglearner.opsagent.core.alerting;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * What happens when an alert cannot be stored. A message that cannot be read goes to {@code <topic>.dlt} at once:
 * reading it again would fail again. Anything else, typically the database being unavailable, is retried with backoff
 * (1 s doubling to 30 s) for as long as it takes, holding the partition: alerts are not dropped during an outage.
 */
@Configuration(proxyBeanMethods = false)
class AlertConsumerConfiguration {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafka) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(record.topic() + ".dlt", -1));
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2);
        backOff.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetters, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
