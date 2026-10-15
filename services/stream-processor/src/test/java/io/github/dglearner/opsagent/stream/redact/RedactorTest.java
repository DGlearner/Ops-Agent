package io.github.dglearner.opsagent.stream.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RedactorTest {

    private final Redactor redactor = new Redactor();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "password=hunter2 user=bob                       | password=*** user=bob",
            "{\"api_key\": \"abc-123\", \"ok\": 1}           | {\"api_key\": ***, \"ok\": 1}",
            "Authorization: Bearer eyJhbGciOi.payload.sig     | Authorization: Bearer ***",
            "jdbc url mysql://app:pa55@db-01:3306/x          | jdbc url mysql://app:***@db-01:3306/x",
            "key AKIAABCDEFGHIJKLMNOP used                   | key *** used",
            "jwt eyJhbGciOiJIUzI1.eyJzdWIiOiIx.c2lnbmF0dXJl  | jwt ***",
            "PWD=/home/nginx ; USER=root                     | PWD=/home/nginx ; USER=root",
            "cache key=feature:90 result=miss                | cache key=feature:90 result=miss",
            "tokens=5 remaining                              | tokens=5 remaining"})
    void masksSecretsOnly(String input, String expected) {
        assertThat(redactor.redact(input.strip())).isEqualTo(expected.strip());
    }

    @Test
    void masksSecretFieldsByName() {
        Map<String, Object> fields = redactor.redactFields(Map.of("Password", "x", "client-secret", "y", "port", 22));

        assertThat(fields).containsEntry("Password", "***").containsEntry("client-secret", "***")
                .containsEntry("port", 22);
    }
}
