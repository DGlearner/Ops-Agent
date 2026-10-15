package io.github.dglearner.opsagent.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Dependency information for one service. Topic {@link Topics#TOPOLOGY_CHANGELOG} (compacted), key = {@code service}.
 *
 * @param groupKey the dependency group the service belongs to, computed by core-service
 * @param version  increases with every change; consumers ignore older versions
 */
public record TopologyEntry(String service, String groupKey, List<String> dependsOn, String team, long version) {

    public TopologyEntry {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(groupKey, "groupKey");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
