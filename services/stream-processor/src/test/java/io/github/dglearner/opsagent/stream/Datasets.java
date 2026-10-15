package io.github.dglearner.opsagent.stream;

import io.github.dglearner.opsagent.contracts.OpsJson;
import io.github.dglearner.opsagent.contracts.RawLogRecord;
import io.github.dglearner.opsagent.contracts.SourceType;
import io.github.dglearner.opsagent.contracts.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Reads the JSONL datasets in the repository's datasets/ directory as raw records, the way ingest-service would. */
public final class Datasets {

    public static final Instant RECEIVED_AT = Instant.parse("2026-01-01T01:00:00Z");

    public static List<RawLogRecord> load(String fileName) {
        Path file = Path.of(System.getProperty("datasets.dir")).resolve(fileName);
        List<RawLogRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode request = OpsJson.mapper().readTree(line);
                SourceType type = OpsJson.mapper().treeToValue(request.get("source_type"), SourceType.class);
                for (JsonNode log : request.get("logs")) {
                    records.add(new RawLogRecord(Ids.newId(), null, "dataset", type, request.get("host").asString(),
                            request.path("service").asString(null), request.path("source_name").asString(null),
                            log.asString(), RECEIVED_AT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    public static RawLogRecord raw(SourceType type, String host, String service, String message) {
        return new RawLogRecord(Ids.newId(), null, "test", type, host, service, null, message, RECEIVED_AT);
    }

    private Datasets() {
    }
}
