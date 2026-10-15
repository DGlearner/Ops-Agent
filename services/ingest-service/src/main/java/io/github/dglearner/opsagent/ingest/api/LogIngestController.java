package io.github.dglearner.opsagent.ingest.api;

import io.github.dglearner.opsagent.ingest.auth.ApiKeyInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
public class LogIngestController {

    private final LogIngestService ingestService;

    public LogIngestController(LogIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Operation(summary = "Ingest a batch of raw log lines",
            description = "Returns 202 once every line has been acknowledged by Kafka.")
    @PostMapping
    public ResponseEntity<IngestResponse> ingest(
            @Valid @RequestBody LogBatchRequest request,
            @Parameter(hidden = true) @RequestAttribute(ApiKeyInterceptor.SOURCE_ID_ATTRIBUTE) String sourceId,
            @Parameter(description = "Makes retries produce the same event ids")
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Pattern(regexp = "[\\x21-\\x7E]{1,128}", message = "must be 1-128 visible ASCII characters")
            String idempotencyKey) {
        return ResponseEntity.accepted().body(ingestService.ingest(sourceId, request, idempotencyKey));
    }
}
