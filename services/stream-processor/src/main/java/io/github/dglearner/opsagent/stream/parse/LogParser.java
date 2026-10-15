package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import java.util.Optional;

/** Parses the lines of one {@link SourceType}. Implementations are stateless and thread-safe. */
public interface LogParser {

    SourceType sourceType();

    /** Empty when the line does not have the expected format. */
    Optional<ParsedLog> parse(RawLogRecord raw);
}
