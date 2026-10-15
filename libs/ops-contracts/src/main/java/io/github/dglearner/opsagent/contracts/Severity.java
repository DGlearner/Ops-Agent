package io.github.dglearner.opsagent.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Severity in ascending order, so {@link #compareTo} can be used for thresholds. */
public enum Severity {
    @JsonProperty("info") INFO,
    @JsonProperty("low") LOW,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("high") HIGH,
    @JsonProperty("critical") CRITICAL;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }

    public static Severity max(Severity a, Severity b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
