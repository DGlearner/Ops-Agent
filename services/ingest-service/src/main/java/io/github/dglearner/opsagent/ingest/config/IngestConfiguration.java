package io.github.dglearner.opsagent.ingest.config;

import io.github.dglearner.opsagent.contracts.JsonSerde;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

@Configuration(proxyBeanMethods = false)
class IngestConfiguration {

    /**
     * Keeps Boot's producer factory, which applies connection details from Testcontainers or Docker Compose, and only
     * swaps in the contract serializer. The application publishes nothing but {@link RawLogRecord}.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer rawLogSerializers() {
        return factory -> {
            @SuppressWarnings("unchecked")
            var typed = (DefaultKafkaProducerFactory<String, RawLogRecord>) factory;
            typed.setKeySerializer(new StringSerializer());
            typed.setValueSerializer(JsonSerde.of(RawLogRecord.class).serializer());
        };
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** springdoc builds schemas with swagger-core's own (Jackson 2) mapper; give it the same snake_case naming. */
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    OpenAPI ingestOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Ops-Agent ingest-service").version("v1"))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
