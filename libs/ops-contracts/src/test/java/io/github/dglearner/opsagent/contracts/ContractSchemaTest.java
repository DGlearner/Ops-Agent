package io.github.dglearner.opsagent.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Java records must serialize to JSON that the shared schemas in contracts/kafka accept. */
class ContractSchemaTest {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    static Stream<Arguments> samples() {
        return Stream.of(
                Arguments.of("raw-log-record", Samples.raw()),
                Arguments.of("normalized-event", Samples.normalized()),
                Arguments.of("unparsed-log", Samples.unparsed()),
                Arguments.of("rule-definition", Samples.rule()),
                Arguments.of("topology-entry", Samples.topology()),
                Arguments.of("alert", Samples.alert()),
                Arguments.of("incident-candidate", Samples.candidate()),
                Arguments.of("agent-task", Samples.agentTask()),
                Arguments.of("agent-result", Samples.agentResult()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void recordMatchesSchema(String schemaName, Object sample) throws IOException {
        JsonNode json = OpsJson.mapper().valueToTree(sample);

        List<Error> errors = schema(schemaName).validate(json);

        assertThat(errors).as("schema errors for %s: %s", schemaName, json).isEmpty();
    }

    @Test
    void schemaRejectsMissingRequiredField() throws IOException {
        ObjectNode json = OpsJson.mapper().valueToTree(Samples.raw());
        json.remove("host");

        assertThat(schema("raw-log-record").validate(json)).isNotEmpty();
    }

    @Test
    void everySchemaFileIsCoveredByASample() throws IOException {
        List<String> covered = samples().map(a -> (String) a.get()[0]).toList();
        try (Stream<Path> files = Files.list(contractsDir())) {
            List<String> schemas = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".schema.json"))
                    .map(n -> n.substring(0, n.length() - ".schema.json".length()))
                    .toList();
            assertThat(covered).containsExactlyInAnyOrderElementsOf(schemas);
        }
    }

    private static Schema schema(String name) throws IOException {
        return REGISTRY.getSchema(Files.readString(contractsDir().resolve(name + ".schema.json")));
    }

    private static Path contractsDir() {
        return Path.of(System.getProperty("contracts.dir"));
    }
}
