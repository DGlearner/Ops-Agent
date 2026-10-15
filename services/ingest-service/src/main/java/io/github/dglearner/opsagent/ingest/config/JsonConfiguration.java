package io.github.dglearner.opsagent.ingest.config;

import io.github.dglearner.opsagent.contracts.OpsJson;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP JSON follows the same conventions as Kafka messages (snake_case, ISO-8601). */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer opsJsonConventions() {
        return OpsJson::configure;
    }
}
