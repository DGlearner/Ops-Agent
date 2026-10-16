package io.github.dglearner.opsagent.ingest.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dglearner.opsagent.ingest.auth.ApiKeys;
import io.github.dglearner.opsagent.ingest.auth.SourceRegistry;
import io.github.dglearner.opsagent.ingest.config.IngestProperties;
import io.github.dglearner.opsagent.ingest.config.JsonConfiguration;
import io.github.dglearner.opsagent.ingest.publish.PublishFailedException;
import io.github.dglearner.opsagent.ingest.ratelimit.RateLimitExceededException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.unit.DataSize;

@WebMvcTest(LogIngestController.class)
@Import({JsonConfiguration.class, LogIngestControllerTest.TestSources.class})
class LogIngestControllerTest {

    private static final String KEY = "test-key";
    private static final String BODY = """
            {"source_type":"linux","host":"app-01","service":"sshd","source_name":"/var/log/auth.log",
             "logs":["Jan 01 00:01:43 app-01 sshd[1]: Failed password for root from 198.51.100.1 port 1 ssh2",
                     "Jan 01 00:01:44 app-01 sshd[1]: Failed password for root from 198.51.100.1 port 2 ssh2"]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LogIngestService ingestService;

    @TestConfiguration
    static class TestSources {

        @Bean
        IngestProperties ingestProperties() {
            return new IngestProperties(DataSize.ofKilobytes(4), Duration.ofSeconds(1),
                    List.of(new IngestProperties.Source("test-source", ApiKeys.sha256Hex(KEY))),
                    new IngestProperties.RateLimit(true, 100, 10));
        }

        @Bean
        SourceRegistry sourceRegistry(IngestProperties properties) {
            return new SourceRegistry(properties);
        }
    }

    @Test
    void acceptsBatchAndPassesTheResolvedSource() throws Exception {
        UUID requestId = UUID.randomUUID();
        given(ingestService.ingest(eq("test-source"), any(), isNull())).willReturn(new IngestResponse(2, requestId));

        mvc.perform(logs(BODY).header("X-Api-Key", KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.request_id").value(requestId.toString()));

        verify(ingestService).ingest(eq("test-source"), any(LogBatchRequest.class), isNull());
    }

    @Test
    void passesIdempotencyKeyThrough() throws Exception {
        given(ingestService.ingest(any(), any(), eq("batch-42"))).willReturn(new IngestResponse(2, UUID.randomUUID()));

        mvc.perform(logs(BODY).header("X-Api-Key", KEY).header("Idempotency-Key", "batch-42"))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsMissingOrUnknownApiKey() throws Exception {
        mvc.perform(logs(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey"))
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        mvc.perform(logs(BODY).header("X-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ingestService);
    }

    @Test
    void reportsFieldErrors() throws Exception {
        mvc.perform(logs("{\"source_type\":\"linux\",\"logs\":[]}").header("X-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'host')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'logs')]").exists());
        // Field names are reported as clients write them, in snake_case.
        mvc.perform(logs("{\"host\":\"h\",\"logs\":[\"x\"]}").header("X-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'source_type')]").exists());
    }

    @Test
    void rejectsUnknownSourceTypeAndMalformedJson() throws Exception {
        mvc.perform(logs(BODY.replace("\"linux\"", "\"windows\"")).header("X-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        mvc.perform(logs("{not json").header("X-Api-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void rejectsInvalidIdempotencyKey() throws Exception {
        mvc.perform(logs(BODY).header("X-Api-Key", KEY).header("Idempotency-Key", "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsBodiesOverTheSizeLimit() throws Exception {
        String big = BODY.replace("Failed password", "Failed password " + "x".repeat(5000));

        mvc.perform(logs(big).header("X-Api-Key", KEY))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void answers429WithRetryAfterWhenRateLimited() throws Exception {
        given(ingestService.ingest(any(), any(), any()))
                .willThrow(new RateLimitExceededException(Duration.ofMillis(1500)));

        mvc.perform(logs(BODY).header("X-Api-Key", KEY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void answers503WhenKafkaIsUnavailable() throws Exception {
        given(ingestService.ingest(any(), any(), any()))
                .willThrow(new PublishFailedException("down", new RuntimeException()));

        mvc.perform(logs(BODY).header("X-Api-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("KAFKA_UNAVAILABLE"));
    }

    private static MockHttpServletRequestBuilder logs(String body) {
        return post("/api/v1/logs").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
