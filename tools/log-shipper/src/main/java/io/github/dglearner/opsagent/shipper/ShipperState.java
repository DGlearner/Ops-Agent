package io.github.dglearner.opsagent.shipper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reading positions per file name, kept in a small JSON file so a restarted shipper resumes where it left off. Written
 * to a temporary file and moved into place, so a crash mid-write leaves the previous state, never a broken one.
 */
final class ShipperState {

    /** Where one file was read up to. */
    record Position(String identity, long generation, long offset) {
    }

    private final Path file;
    private final JsonMapper json = JsonMapper.builder().build();

    ShipperState(Path file) {
        this.file = file;
    }

    Map<String, Position> load() throws IOException {
        Map<String, Position> positions = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return positions;
        }
        JsonNode root = json.readTree(Files.readString(file));
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode p = entry.getValue();
            positions.put(entry.getKey(), new Position(p.path("identity").asString(), p.path("generation").asLong(),
                    p.path("offset").asLong()));
        }
        return positions;
    }

    void save(Map<String, Position> positions) throws IOException {
        ObjectNode root = json.createObjectNode();
        positions.forEach((name, p) -> root.putObject(name).put("identity", p.identity())
                .put("generation", p.generation()).put("offset", p.offset()));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
