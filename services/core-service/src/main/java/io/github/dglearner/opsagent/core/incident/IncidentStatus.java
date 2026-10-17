package io.github.dglearner.opsagent.core.incident;

import java.util.Locale;
import java.util.Set;

/**
 * An incident's lifecycle (docs/project-design.md §8.5): forward through the active states, to resolved from any
 * of them, then closed; a resolved incident can be reopened until it is closed.
 */
public enum IncidentStatus {
    OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED, CLOSED;

    static final Set<IncidentStatus> ACTIVE = Set.of(OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED);

    boolean canMoveTo(IncidentStatus to) {
        return switch (this) {
            case OPEN -> Set.of(ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED).contains(to);
            case ACKNOWLEDGED -> Set.of(INVESTIGATING, MITIGATED, RESOLVED).contains(to);
            // A mitigation that did not hold sends the incident back to investigation.
            case INVESTIGATING -> Set.of(MITIGATED, RESOLVED).contains(to);
            case MITIGATED -> Set.of(INVESTIGATING, RESOLVED).contains(to);
            case RESOLVED -> Set.of(OPEN, CLOSED).contains(to);
            case CLOSED -> false;
        };
    }

    String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    static IncidentStatus of(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT));
    }
}
