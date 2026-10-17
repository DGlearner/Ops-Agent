package io.github.dglearner.shop.lab;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class GroundTruthTest {

    @TempDir
    Path dir;

    @Test
    void theLastVersionOfEachEpisodeSurvivesARestart() {
        LabProperties properties = new LabProperties(null, null, null, null, null, null, null,
                dir.resolve("lab/ground-truth.jsonl"), null, Duration.ofMinutes(10));
        JsonMapper json = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        Instant start = Instant.parse("2026-10-17T10:00:00Z");
        Episode episode = new Episode("e1", "fault", "mysql-down", "shop-mysql", "d", "e", 1, start, null, true);

        GroundTruth first = new GroundTruth(properties, json);
        first.record(episode);
        first.record(episode.ended(start.plusSeconds(30), false));

        assertThat(new GroundTruth(properties, json).episodes()).singleElement().satisfies(e -> {
            assertThat(e.endedAt()).isEqualTo(start.plusSeconds(30));
            assertThat(e.actionsOk()).isFalse();
        });
    }
}
