package io.github.dglearner.opsagent.stream.parse;

import io.github.dglearner.opsagent.contracts.NormalizedEvent;
import io.github.dglearner.opsagent.contracts.UnparsedLog;

/** Result of parsing one raw record; routes the record to {@code logs.normalized} or {@code logs.unparsed}. */
public sealed interface ParseOutcome {

    record Parsed(NormalizedEvent event) implements ParseOutcome {
    }

    record Unparsed(UnparsedLog log) implements ParseOutcome {
    }
}
