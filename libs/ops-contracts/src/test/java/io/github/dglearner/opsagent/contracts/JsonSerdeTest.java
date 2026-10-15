package io.github.dglearner.opsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

class JsonSerdeTest {

    private final JsonSerde<RuleDefinition> serde = JsonSerde.of(RuleDefinition.class);

    @Test
    void roundTrips() {
        byte[] bytes = serde.serializer().serialize(Topics.RULES_CHANGELOG, Samples.rule());

        assertThat(serde.deserializer().deserialize(Topics.RULES_CHANGELOG, bytes)).isEqualTo(Samples.rule());
    }

    @Test
    void passesTombstonesThrough() {
        assertThat(serde.serializer().serialize(Topics.RULES_CHANGELOG, null)).isNull();
        assertThat(serde.deserializer().deserialize(Topics.RULES_CHANGELOG, null)).isNull();
    }

    @Test
    void reportsMalformedAndInvalidRecordsAsSerializationErrors() {
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        byte[] badWindow = OpsJson.toJson(Samples.rule()).replace("PT5M", "PT2M").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> serde.deserializer().deserialize("t", notJson))
                .isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> serde.deserializer().deserialize("t", badWindow))
                .isInstanceOf(SerializationException.class);
    }
}
