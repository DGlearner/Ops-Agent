package io.github.dglearner.opsagent.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON mapping used for every Kafka message: snake_case names, ISO-8601 times and durations, nulls omitted,
 * unknown fields ignored so that producers can add fields without breaking older consumers.
 */
public final class OpsJson {

    private static final JsonMapper MAPPER = configure(JsonMapper.builder()).build();

    /** Applies the shared settings, so a Spring-managed mapper can be configured the same way. */
    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        return builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static byte[] toBytes(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    public static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T fromBytes(byte[] bytes, Class<T> type) {
        return MAPPER.readValue(bytes, type);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    private OpsJson() {
    }
}
