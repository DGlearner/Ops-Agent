package io.github.dglearner.opsagent.stream.config;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/** UP while the Kafka Streams client is running or rebalancing; DOWN once it has stopped or failed. */
@Component("kafkaStreams")
class KafkaStreamsHealthIndicator extends AbstractHealthIndicator {

    private final StreamsBuilderFactoryBean factory;

    KafkaStreamsHealthIndicator(StreamsBuilderFactoryBean factory) {
        this.factory = factory;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        KafkaStreams streams = factory.getKafkaStreams();
        if (streams == null) {
            builder.down().withDetail("state", "NOT_STARTED");
            return;
        }
        KafkaStreams.State state = streams.state();
        (state.isRunningOrRebalancing() ? builder.up() : builder.down()).withDetail("state", state.name());
    }
}
