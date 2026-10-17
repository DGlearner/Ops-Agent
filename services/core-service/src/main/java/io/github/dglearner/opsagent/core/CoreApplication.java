package io.github.dglearner.opsagent.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ops-Agent's backend, a modular monolith (docs/project-design.md §6.4): each top-level package is a module that
 * others use only through its public types. {@code alerting} comes first; incidents, rules, catalog and identity
 * follow.
 */
@SpringBootApplication
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
