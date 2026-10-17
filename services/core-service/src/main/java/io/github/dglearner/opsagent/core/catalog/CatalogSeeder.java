package io.github.dglearner.opsagent.core.catalog;

import io.github.dglearner.opsagent.contracts.OpsJson;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads {@code catalog/topology.json} (the shop's services and their dependencies) at startup when
 * {@code ops.catalog.seed=true}: entries in the file are created or updated, others are left alone.
 */
@Component
@ConditionalOnBooleanProperty("ops.catalog.seed")
class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    record Seed(List<Entry> services) {
        record Entry(String name, String kind, String description, List<String> dependsOn) {
        }
    }

    private final Catalog catalog;

    CatalogSeeder(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        Seed seed;
        try (InputStream in = new ClassPathResource("catalog/topology.json").getInputStream()) {
            seed = OpsJson.fromBytes(in.readAllBytes(), Seed.class);
        }
        seed.services().forEach(e -> catalog.putService(e.name(),
                ServiceKind.valueOf(e.kind().toUpperCase(Locale.ROOT)), e.description()));
        seed.services().forEach(e -> catalog.replaceDependencies(e.name(), e.dependsOn()));
        log.info("Catalog seeded with {} entries from catalog/topology.json", seed.services().size());
    }
}
