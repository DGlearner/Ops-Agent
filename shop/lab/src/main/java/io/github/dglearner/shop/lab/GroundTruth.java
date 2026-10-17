package io.github.dglearner.shop.lab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The episodes, kept in memory and appended to a JSON lines file on every change, one line per version of an
 * episode; on startup the last version of each wins. The file is the ground truth for scoring, outside every log
 * and metric of the shop.
 */
@Component
class GroundTruth {

    private final Path file;
    private final JsonMapper json;
    private final Map<String, Episode> episodes = new LinkedHashMap<>();

    GroundTruth(LabProperties properties, JsonMapper json) {
        this.file = properties.groundTruth();
        this.json = json;
        load();
    }

    synchronized void record(Episode episode) {
        episodes.put(episode.id(), episode);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, json.writeValueAsString(episode) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the ground truth to " + file, e);
        }
    }

    synchronized List<Episode> episodes() {
        return new ArrayList<>(episodes.values());
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    Episode episode = json.readValue(line, Episode.class);
                    episodes.put(episode.id(), episode);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the ground truth in " + file, e);
        }
    }
}
