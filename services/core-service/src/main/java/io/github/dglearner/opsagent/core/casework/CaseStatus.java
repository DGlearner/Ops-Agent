package io.github.dglearner.opsagent.core.casework;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A case's lifecycle (docs/agent-design.md §2.1, §13.1). Only a person signs a case off as resolved. */
public enum CaseStatus {
    OPEN, DIAGNOSING, MITIGATING, VERIFYING, MONITORING, RESOLVED, HANDED_OVER;

    private static final Map<CaseStatus, Set<CaseStatus>> NEXT = Map.of(
            OPEN, EnumSet.of(DIAGNOSING, MITIGATING, HANDED_OVER, RESOLVED),
            DIAGNOSING, EnumSet.of(MITIGATING, HANDED_OVER, RESOLVED),
            MITIGATING, EnumSet.of(VERIFYING, DIAGNOSING, HANDED_OVER, RESOLVED),
            VERIFYING, EnumSet.of(MONITORING, DIAGNOSING, MITIGATING, HANDED_OVER, RESOLVED),
            MONITORING, EnumSet.of(DIAGNOSING, MITIGATING, HANDED_OVER, RESOLVED),
            HANDED_OVER, EnumSet.of(DIAGNOSING, RESOLVED),
            RESOLVED, EnumSet.noneOf(CaseStatus.class));

    public boolean canMoveTo(CaseStatus to) {
        return NEXT.get(this).contains(to);
    }

    public boolean active() {
        return this != RESOLVED;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CaseStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
