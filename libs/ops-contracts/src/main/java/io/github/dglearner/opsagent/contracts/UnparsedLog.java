package io.github.dglearner.opsagent.contracts;

import java.util.Objects;

/** A raw line no parser could handle. Topic {@link Topics#LOGS_UNPARSED}, key = {@code source_type}. */
public record UnparsedLog(RawLogRecord raw, String reason) {

    public UnparsedLog {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(reason, "reason");
    }
}
