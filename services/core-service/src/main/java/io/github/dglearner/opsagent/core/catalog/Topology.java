package io.github.dglearner.opsagent.core.catalog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dependency graph at one moment: {@code edges} maps each entry to what it depends on directly. Cycles are
 * allowed; reachability handles them.
 */
public record Topology(Map<String, Kind> kinds, Map<String, List<String>> edges) {

    /** An entry's kind and description. */
    public record Kind(ServiceKind kind, String description) {
    }

    /** {@code name} itself and everything it depends on, directly or through others. */
    public Set<String> dependencyClosure(String name) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(name));
        while (!todo.isEmpty()) {
            String next = todo.pop();
            if (seen.add(next)) {
                todo.addAll(edges.getOrDefault(next, List.of()));
            }
        }
        return seen;
    }

    /** Whether one of the two depends on the other, directly or not, or they are the same. */
    public boolean dependencyPathBetween(String a, String b) {
        return dependencyClosure(a).contains(b) || dependencyClosure(b).contains(a);
    }
}
