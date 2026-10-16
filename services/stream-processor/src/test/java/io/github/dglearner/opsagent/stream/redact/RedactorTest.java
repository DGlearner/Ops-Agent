package io.github.dglearner.opsagent.stream.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "password=\"fake-secret                          | password=\"***",
            "login failed api_key='fake-15                   | login failed api_key='***",
            "{\"user\":\"bob\",\"password\":\"fake-16          | {\"user\":\"bob\",\"password\":\"***",
            "{\"user\":\"bob\",\"password\":\"ab\\\"fake-17     | {\"user\":\"bob\",\"password\":\"***"})
    void masksASecretValueCutOffBeforeItsClosingQuote(String input, String expected) {
        assertThat(redactor.redact(input.strip())).isEqualTo(expected.strip());
    }

    /**
     * Ingest accepts lines of up to 16 KB. Masking must not need stack in proportion to the line: a StackOverflowError
     * in the stream thread would stop processing. The work runs on a thread with a deliberately small stack.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "password=\"%s\"",
            "password=\"%s",
            "{\"password\":\"%s\",\"user\":\"bob\"}",
            "{\"message\":\"login password=%s\"}",
            "token=%s"})
    void masksLongLinesWithoutDeepRecursion(String template) throws InterruptedException {
        for (String value : List.of("fake-" + "a".repeat(16 * 1024), "fake-" + "a\\\"".repeat(4 * 1024))) {
            String input = template.formatted(value);

            String output = onSmallStack(() -> redactor.redact(input));

            assertThat(output).doesNotContain("fake-");
        }
    }

    @Test
    void longValueOfAnOrdinaryFieldNeedsNoDeepRecursionEither() throws InterruptedException {
        String input = "note=\"" + "a\\\"".repeat(4 * 1024) + "\" password=fake-x";

        assertThat(onSmallStack(() -> redactor.redact(input))).endsWith("\" password=***");
    }

    private static String onSmallStack(java.util.function.Supplier<String> work) throws InterruptedException {
        AtomicReference<Object> result = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                result.set(work.get());
            } catch (Throwable e) {
                result.set(e);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        assertThat(result.get()).as("result, or the error thrown").isInstanceOf(String.class);
        return (String) result.get();
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
