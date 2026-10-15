package io.github.dglearner.opsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OpsJsonTest {

    @Test
    void writesSnakeCaseNamesWireEnumsAndIsoTimes() {
        JsonNode json = OpsJson.mapper().valueToTree(Samples.rule());

        assertThat(json.get("rule_id").asString()).isEqualTo("ssh-brute-force");
        assertThat(json.get("severity").asString()).isEqualTo("medium");
        assertThat(json.get("window").asString()).isEqualTo("PT5M");
        assertThat(json.get("match").get("source_types").get(0).asString()).isEqualTo("linux");
        assertThat(OpsJson.mapper().valueToTree(Samples.raw()).get("received_at").asString())
                .isEqualTo("2026-01-01T00:05:00Z");
    }

    @Test
    void omitsNullFields() {
        RuleDefinition.Match match = Samples.rule().match();

        assertThat(OpsJson.toJson(match)).doesNotContain("min_severity");
    }

    @Test
    void roundTripsEveryRecord() {
        for (Object sample : new Object[] {Samples.raw(), Samples.normalized(), Samples.unparsed(), Samples.rule(),
                Samples.topology(), Samples.alert(), Samples.candidate()}) {
            Object back = OpsJson.fromBytes(OpsJson.toBytes(sample), sample.getClass());
            assertThat(back).isEqualTo(sample);
        }
    }

    @Test
    void ignoresUnknownFields() {
        String json = OpsJson.toJson(Samples.topology()).replace("{", "{\"added_later\":true,");

        assertThat(OpsJson.fromJson(json, TopologyEntry.class)).isEqualTo(Samples.topology());
    }
}
