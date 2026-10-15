package io.github.dglearner.opsagent.contracts;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.core.JacksonException;

/** Kafka serde for contract records using {@link OpsJson}. Null values pass through as tombstones. */
public final class JsonSerde<T> implements Serde<T> {

    private final Class<T> type;

    private JsonSerde(Class<T> type) {
        this.type = type;
    }

    public static <T> JsonSerde<T> of(Class<T> type) {
        return new JsonSerde<>(type);
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> data == null ? null : OpsJson.toBytes(data);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return OpsJson.fromBytes(bytes, type);
            } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
                // Records validate in their constructors; surface both kinds of failure as Kafka errors so the
                // configured deserialization exception handler decides what happens to the record.
                throw new SerializationException("Cannot read " + type.getSimpleName() + " from " + topic, e);
            }
        };
    }
}
