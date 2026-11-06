package io.github.dglearner.opsagent.core.logs;

import java.util.List;

/** One page of log lines, newest first; {@code nextCursor} fetches the next page and is absent on the last one. */
public record LogPage(List<LogEventView> items, String nextCursor) {
}
