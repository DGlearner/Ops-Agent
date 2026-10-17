package io.github.dglearner.opsagent.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Modules use one another only through their public types (docs/project-design.md §6.4). */
class ModularityTest {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(CoreApplication.class).verify();
    }
}
