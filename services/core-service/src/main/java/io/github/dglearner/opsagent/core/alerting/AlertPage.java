package io.github.dglearner.opsagent.core.alerting;

import java.util.List;

/** One page of alerts, newest first; {@code nextCursor} fetches the next page and is absent on the last one. */
public record AlertPage(List<AlertView> items, String nextCursor) {
}
