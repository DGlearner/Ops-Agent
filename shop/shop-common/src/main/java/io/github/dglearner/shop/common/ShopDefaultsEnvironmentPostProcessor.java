package io.github.dglearner.shop.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds {@code shop-defaults.yaml} with the lowest precedence, so every service shares the same logging, tracing,
 * metrics and JSON settings and can still override any of them in its own application.yaml.
 */
public class ShopDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            new YamlPropertySourceLoader()
                    .load("shop-defaults", new ClassPathResource("shop-defaults.yaml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read shop-defaults.yaml", e);
        }
    }
}
