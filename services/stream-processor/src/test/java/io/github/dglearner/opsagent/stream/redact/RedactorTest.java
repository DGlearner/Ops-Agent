package io.github.dglearner.opsagent.stream.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RedactorTest {

    private final Redactor redactor = new Redactor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "password=hunter2 user=bob                       | password=*** user=bob",
            "api_key: abc-123, ok: 1                         | api_key: ***, ok: 1",
            "Authorization: Bearer eyJhbGciOi.payload.sig     | Authorization: Bearer ***",
            "sent \"Authorization\": \"Bearer fake-opaque-1\" | sent \"Authorization\": \"Bearer ***\"",
            "{\"password\":\"ab\\\"cd-fake\", \"user\":\"bob\"   | {\"password\":\"***\", \"user\":\"bob\"",
            "jdbc url mysql://app:pa55@db-01:3306/x          | jdbc url mysql://app:***@db-01:3306/x",
            "key AKIAABCDEFGHIJKLMNOP used                   | key *** used",
            "jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIx.c2lnbmF0dXJl  | jwt ***",
            "PWD=/home/nginx ; USER=root                     | PWD=/home/nginx ; USER=root",
            "cache key=feature:90 result=miss                | cache key=feature:90 result=miss",
            "tokens=5 remaining                              | tokens=5 remaining"})
    void masksSecretsOnly(String input, String expected) {
        assertThat(redactor.redact(input.strip())).isEqualTo(expected.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Authorization\":\"Bearer fake-opaque-1\"}",
            "{\"request\":{\"headers\":{\"authorization\":\"Basic ZmFrZTpmYWtlLTI=\"}},\"status\":401}",
            "{\"password\":\"ab\\\"cd-fake-3\",\"user\":\"bob\"}",
            "{\"events\":[{\"Client-Secret\":\"fake-4\"},\"token=fake-5\"],\"api_key\":12345}",
            "[{\"message\":\"login failed password=fake-6\"}]",
            "{\"message\":\"GET https://bob:fake-7@example.test/\",\"session_id\":{\"value\":\"fake-8\"}}"})
    void masksJsonDocumentsFieldByFieldAndKeepsThemValid(String input) {
        String output = redactor.redact(input);

        assertThat(output).doesNotContain("fake-", "ZmFrZTpmYWtl", "12345");
        assertThat(JsonMapper.builder().build().readTree(output)).as(output).isNotNull();
        assertThat(output).contains(Redactor.MASK);
    }

    @Test
    void leavesJsonWithoutSecretsAsItWas() {
        String input = "{ \"level\": \"INFO\",  \"message\": \"order placed\", \"amount\": 1.50 }";

        assertThat(redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void masksTruncatedJsonAsText() {
        assertThat(redactor.redact("{\"user\":\"bob\",\"password\":\"fake-9\",\"msg\":\"cut off"))
                .isEqualTo("{\"user\":\"bob\",\"password\":\"***\",\"msg\":\"cut off");
    }

    @Test
    void masksNestedFields() {
        Map<String, Object> fields = redactor.redactFields(Map.of(
                "headers", Map.of("Authorization", "Bearer fake-10"),
                "args", List.of("password=fake-11", 3)));

        assertThat(fields.toString()).doesNotContain("fake-");
        assertThat(fields.get("args")).isEqualTo(List.of("password=***", 3));
    }

    @Test
    void masksSecretFieldsByName() {
        Map<String, Object> fields = redactor.redactFields(Map.of("Password", "x", "client-secret", "y", "port", 22));

        assertThat(fields).containsEntry("Password", "***").containsEntry("client-secret", "***")
                .containsEntry("port", 22);
    }
}
