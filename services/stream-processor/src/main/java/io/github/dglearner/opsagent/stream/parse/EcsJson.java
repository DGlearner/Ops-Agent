package io.github.dglearner.opsagent.stream.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One structured log line as a JSON object, such as the ECS lines Spring Boot writes
 * ({@code logging.structured.format.file=ecs}). Nested objects are flattened to dotted names, so
 * {@code {"log":{"level":"WARN"}}} and {@code {"log.level":"WARN"}} read the same: Spring Boot nests ECS fields and
 * key-value pairs, but writes renamed MDC entries such as {@code trace.id} flat.
 */
final class EcsJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The fields of the line, flattened; empty if it is not a JSON object. */
    static Optional<Map<String, Object>> parse(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        flatten("", root, fields);
        return Optional.of(fields);
    }

    private static void flatten(String prefix, JsonNode node, Map<String, Object> into) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String name = prefix + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                flatten(name + ".", value, into);
            } else if (!value.isNull()) {
                into.put(name, value(value));
            }
        }
    }

    private static Object value(JsonNode node) {
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(item.isValueNode() ? value(item) : item.toString()));
            return items;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asString();
    }

    private EcsJson() {
    }
}
