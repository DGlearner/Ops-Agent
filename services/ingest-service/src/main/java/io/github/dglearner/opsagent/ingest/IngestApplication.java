package io.github.dglearner.opsagent.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
