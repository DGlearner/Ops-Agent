package io.github.dglearner.opsagent.core.incident;

import java.util.List;

/** One page of incidents, newest first; {@code nextCursor} is absent on the last page. */
public record IncidentPage(List<IncidentView> items, String nextCursor) {
}
