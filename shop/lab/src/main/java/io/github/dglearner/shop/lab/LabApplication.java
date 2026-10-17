package io.github.dglearner.shop.lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The lab console (docs/shop-design.md §15): a web page to run traffic against the shop, break it on purpose and keep
 * the ground truth of what was done, then score what Ops-Agent made of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LabApplication {

    public static void main(String[] args) {
        SpringApplication.run(LabApplication.class, args);
    }
}
