package io.github.dglearner.opsagent.core.catalog;

import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The catalog module's API: services, databases and the like, and what depends on what. */
@Service
public class Catalog {

    private final CatalogMapper mapper;

    Catalog(CatalogMapper mapper) {
        this.mapper = mapper;
    }

    /** The current graph. Read from the database each time: it is small, and always current. */
    public Topology topology() {
        Map<String, Topology.Kind> kinds = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.services()) {
            kinds.put((String) row.get("name"), new Topology.Kind(
                    ServiceKind.valueOf(((String) row.get("kind")).toUpperCase(Locale.ROOT)),
                    (String) row.get("description")));
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.dependencies()) {
            edges.computeIfAbsent((String) row.get("service"), s -> new ArrayList<>())
                    .add((String) row.get("depends_on"));
        }
        return new Topology(kinds, edges);
    }

    @Transactional
    public void putService(String name, ServiceKind kind, String description) {
        mapper.upsertService(name, kind.name().toLowerCase(Locale.ROOT), description);
    }

    /** Replaces everything {@code service} depends on; every name must be in the catalog already. */
    @Transactional
    public void replaceDependencies(String service, List<String> dependsOn) {
        Set<String> wanted = new LinkedHashSet<>(dependsOn);
        Set<String> known = new HashSet<>(mapper.existing(concat(service, wanted)));
        if (!known.contains(service)) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "No service " + service);
        }
        List<String> unknown = wanted.stream().filter(d -> !known.contains(d)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiProblem(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SERVICE",
                    "Not in the catalog: " + String.join(", ", unknown));
        }
        if (wanted.contains(service)) {
            throw new ApiProblem(HttpStatus.UNPROCESSABLE_CONTENT, "SELF_DEPENDENCY",
                    service + " cannot depend on itself");
        }
        mapper.deleteDependencies(service);
        wanted.forEach(d -> mapper.insertDependency(service, d));
    }

    private static List<String> concat(String first, Set<String> rest) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(rest);
        return all;
    }
}
