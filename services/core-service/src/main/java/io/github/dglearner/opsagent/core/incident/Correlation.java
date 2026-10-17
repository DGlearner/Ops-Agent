package io.github.dglearner.opsagent.core.incident;

import io.github.dglearner.opsagent.core.alerting.AlertStored;
import io.github.dglearner.opsagent.core.catalog.Topology;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which incident a new alert belongs to (docs/project-design.md §8.4).
 *
 * <p>Every alert points at a <em>subject</em>: the callee for a failing call between services (the rule groups by
 * {@code fields.dependency}), otherwise the service the alert was raised for. An alert joins an active incident that
 * had an alert within the correlation gap, if a dependency path connects its subject with a subject of that incident
 * in either direction, or they are the same. A shared dependency alone does not connect two subjects: payment and
 * inventory both use the shop's MySQL, yet a payment fault and an independent inventory fault at the same time stay
 * two incidents. With several candidates the one with the most recent alert wins; incidents are never merged
 * automatically, and no root cause is decided.
 */
final class Correlation {

    /** What the rule engine writes for a group-by attribute an event did not have. */
    private static final String MISSING = "unknown";

    /** An active incident: what its alerts point at, and when it had its first and latest alert. */
    record Candidate(UUID incidentId, Set<String> subjects, Instant openedAt, Instant lastAlertAt) {
    }

    static String subject(AlertStored alert) {
        String dependency = alert.groupValues().get("fields.dependency");
        if (known(dependency)) {
            return dependency;
        }
        return known(alert.service()) ? alert.service() : alert.host();
    }

    /** The incident the alert joins, or empty if it opens a new one. */
    static Optional<UUID> choose(String subject, Instant at, Collection<Candidate> candidates, Topology topology,
            Duration gap) {
        return candidates.stream()
                .filter(c -> !at.isBefore(c.openedAt().minus(gap)) && !at.isAfter(c.lastAlertAt().plus(gap)))
                .filter(c -> c.subjects().stream().anyMatch(s -> topology.dependencyPathBetween(s, subject)))
                .max(Comparator.comparing(Candidate::lastAlertAt))
                .map(Candidate::incidentId);
    }

    /**
     * What every subject of an incident depends on, the subjects themselves left out: a hint where several
     * symptoms may come from, not a root cause. Empty for fewer than two subjects.
     */
    static List<String> sharedDependencies(Collection<String> subjects, Topology topology) {
        if (subjects.size() < 2) {
            return List.of();
        }
        Set<String> shared = null;
        for (String subject : subjects) {
            Set<String> closure = topology.dependencyClosure(subject);
            if (shared == null) {
                shared = new LinkedHashSet<>(closure);
            } else {
                shared.retainAll(closure);
            }
        }
        shared.removeAll(subjects);
        return List.copyOf(shared);
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !value.equals(MISSING);
    }

    private Correlation() {
    }
}
