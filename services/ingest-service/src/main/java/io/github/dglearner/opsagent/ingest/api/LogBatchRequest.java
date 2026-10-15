package io.github.dglearner.opsagent.ingest.api;

import io.github.dglearner.opsagent.contracts.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of {@code POST /api/v1/logs}. Same shape as a line of the v1 datasets, so they can be replayed directly. */
public record LogBatchRequest(
        @NotNull SourceType sourceType,
        @NotBlank @Size(max = 255) String host,
        @Size(min = 1, max = 255) String service,
        @Size(max = 1024) String sourceName,
        @Schema(description = "Raw log lines, oldest first")
        @NotEmpty @Size(max = MAX_LINES) List<@NotNull @Size(max = MAX_LINE_LENGTH) String> logs) {

    public static final int MAX_LINES = 1000;
    public static final int MAX_LINE_LENGTH = 16 * 1024;
}
