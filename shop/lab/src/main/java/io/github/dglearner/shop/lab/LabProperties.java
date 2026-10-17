package io.github.dglearner.shop.lab;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code shop.lab}. */
@ConfigurationProperties("shop.lab")
public record LabProperties(String orderUrl, String inventoryUrl, String paymentUrl, String orderAdmin,
        String inventoryAdmin, String paymentAdmin, String mysqlContainer, Path groundTruth, String opsAgentUrl,
        Duration maxFaultDuration) {

    /** The generator's command-line options that point it at this shop, before any load settings. */
    List<String> generatorArgs() {
        return List.of("--order-url", orderUrl, "--inventory-url", inventoryUrl, "--payment-url", paymentUrl,
                "--order-admin", orderAdmin, "--inventory-admin", inventoryAdmin, "--payment-admin", paymentAdmin,
                "--mysql-container", mysqlContainer);
    }
}
