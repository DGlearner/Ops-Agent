package io.github.dglearner.opsagent.core.catalog;

import io.github.dglearner.opsagent.core.shared.ApiProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/topology} and {@code /api/v1/services/...} (docs/project-design.md §7.3). */
@RestController
@RequestMapping("/api/v1")
class CatalogController {

    record Node(String name, String kind, String description) {
    }

    record Edge(String from, String to) {
    }

    record TopologyView(List<Node> nodes, List<Edge> edges) {
    }

    record ServiceRequest(String kind, String description) {
    }

    record DependenciesRequest(List<String> dependsOn) {
    }

    private final Catalog catalog;

    CatalogController(Catalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/topology")
    TopologyView topology() {
        Topology topology = catalog.topology();
        List<Node> nodes = new ArrayList<>();
        topology.kinds().forEach((name, kind) -> nodes.add(new Node(name, kind.kind().name().toLowerCase(Locale.ROOT),
                kind.description())));
        List<Edge> edges = new ArrayList<>();
        topology.edges().forEach((from, tos) -> tos.forEach(to -> edges.add(new Edge(from, to))));
        return new TopologyView(nodes, edges);
    }

    @PutMapping("/services/{name}")
    void putService(@PathVariable String name, @RequestBody ServiceRequest request) {
        ServiceKind kind;
        try {
            kind = request.kind() == null ? ServiceKind.SERVICE
                    : ServiceKind.valueOf(request.kind().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "kind must be one of service, database, cache, queue, external");
        }
        catalog.putService(name, kind, request.description());
    }

    @PutMapping("/services/{name}/dependencies")
    void putDependencies(@PathVariable String name, @RequestBody DependenciesRequest request) {
        catalog.replaceDependencies(name, request.dependsOn() == null ? List.of() : request.dependsOn());
    }
}
