package io.github.dglearner.opsagent.stream.config;

import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.RuleDefinition;
import io.github.dglearner.opsagent.stream.baseline.BaselineProperties;
import io.github.dglearner.opsagent.stream.parse.ParserRegistry;
import io.github.dglearner.opsagent.stream.topology.OpsTopology;
import java.time.Clock;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

@Configuration(proxyBeanMethods = false)
@EnableKafkaStreams
class StreamConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Registers the topology on the StreamsBuilder that Spring Kafka starts and stops with the application. */
    @Bean
    OpsTopology opsTopology(StreamsBuilder builder, StreamProperties properties, BaselineProperties baseline,
            Clock clock) {
        OpsTopology topology = new OpsTopology(ParserRegistry.withDefaultParsers(), properties, baseline, clock);
        topology.addTo(builder);
        return topology;
    }

    /** The only producer outside Kafka Streams is the rule seeder; it writes {@link RuleDefinition} values. */
    @Bean
    DefaultKafkaProducerFactoryCustomizer ruleSerializers() {
        return factory -> {
            @SuppressWarnings("unchecked")
            var typed = (DefaultKafkaProducerFactory<String, RuleDefinition>) factory;
            typed.setKeySerializer(new StringSerializer());
            typed.setValueSerializer(JsonSerde.of(RuleDefinition.class).serializer());
        };
    }
}
