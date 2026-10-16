package io.github.dglearner.opsagent.stream.redact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Masks credentials before events leave stream-processor, so neither storage nor an LLM sees them. Applied to the
 * message and to every string field; fields whose name is a secret are masked as a whole.
 *
 * <p>A message that is a JSON document (the usual shape of structured application logs) is parsed and masked field by
 * field, at any depth, and stays valid JSON; it is re-serialized only if something was masked. Other text goes through
 * the patterns below, which also keep quoted values quoted. If a JSON message cannot be parsed it is treated as text,
 * so a parse failure never lets it through unmasked.
 *
 * <p>A secret's value is found by a plain scan rather than a regular expression: a pattern for quoted strings with
 * escapes recurses once per character in {@link java.util.regex}, so a long line would overflow the stream thread's
 * stack. A quoted value whose closing quote is missing (the line was cut off) is masked to the end of the text.
 *
 * <p>{@code PWD} is deliberately not treated as a secret: in sudo logs it is the working directory.
 */
public final class Redactor {

    public static final String MASK = "***";

    private static final String SECRET_KEYS =
            "password|passwd|secret|token|api[_-]?key|apikey|access[_-]?key|secret[_-]?key|private[_-]?key"
                    + "|auth[_-]?token|access[_-]?token|refresh[_-]?token|client[_-]?secret|session[_-]?id";

    private static final Set<String> SECRET_FIELD_NAMES = Set.of("password", "passwd", "secret", "token", "api_key",
            "apikey", "access_key", "secret_key", "private_key", "auth_token", "access_token", "refresh_token",
            "client_secret", "session_id", "authorization");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Rule(Pattern pattern, String replacement) {

        String apply(String text) {
            return pattern.matcher(text).replaceAll(replacement);
        }
    }

    // Authorization: Bearer abc / authorization=Basic abc / "Authorization": "Bearer abc"
    private static final Rule AUTHORIZATION = new Rule(
            Pattern.compile("(?i)(authorization\"?\\s*[:=]\\s*\"?)(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]+"),
            "$1$2 " + MASK);

    // password=..., "token": "...", api_key: ... (the key and separator; the value is scanned, see valueEnd)
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)\"?\\b(?:" + SECRET_KEYS + ")\\b\"?\\s*[=:]\\s*");

    private static final List<Rule> TOKENS = List.of(
            // scheme://user:password@host
            new Rule(Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://[^/\\s:@]+:)[^@\\s/]+@"), "$1" + MASK + "@"),
            // JSON Web Tokens
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"), MASK),
            // AWS access key ids
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"), MASK));

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (looksLikeJson(text)) {
            try {
                JsonNode tree = JSON.readTree(text);
                return redactJson(tree) ? JSON.writeValueAsString(tree) : text;
            } catch (JacksonException e) {
                // Not JSON after all, or truncated: mask it as text.
            }
        }
        return redactText(text);
    }

    public Map<String, Object> redactFields(Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach((name, value) -> result.put(name, isSecretName(name) ? MASK : redactValue(value)));
        return result;
    }

    private Object redactValue(Object value) {
        return switch (value) {
            case String s -> redact(s);
            case Map<?, ?> map -> {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((k, v) -> nested.put(String.valueOf(k), v));
                yield redactFields(nested);
            }
            case List<?> list -> {
                List<Object> items = new ArrayList<>(list.size());
                list.forEach(item -> items.add(redactValue(item)));
                yield items;
            }
            case null, default -> value;
        };
    }

    private static String redactText(String text) {
        String result = maskSecretValues(AUTHORIZATION.apply(text));
        for (Rule rule : TOKENS) {
            result = rule.apply(result);
        }
        return result;
    }

    private static String maskSecretValues(String text) {
        Matcher key = SECRET_KEY.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (key.find(from)) {
            int start = key.end();
            int end = valueEnd(text, start);
            from = Math.max(end, key.end());
            if (end == start) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, start).append(masked(text, start, end));
            copied = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * Where the value starting at {@code start} ends: after the closing quote of a double-quoted (with backslash
     * escapes) or single-quoted string, at the end of the text if that quote is missing, else at the next separator.
     */
    private static int valueEnd(String text, int start) {
        if (start >= text.length()) {
            return start;
        }
        char quote = text.charAt(start);
        if (quote == '"' || quote == '\'') {
            for (int i = start + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    return i + 1;
                }
            }
            return text.length();
        }
        int i = start;
        while (i < text.length() && ",;&\"'".indexOf(text.charAt(i)) < 0 && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The mask in the value's own quotes, so a masked JSON or quoted value stays well-formed. */
    private static String masked(String text, int start, int end) {
        char quote = text.charAt(start);
        if (quote != '"' && quote != '\'') {
            return MASK;
        }
        boolean closed = end - start >= 2 && text.charAt(end - 1) == quote;
        return closed ? quote + MASK + quote : quote + MASK;
    }

    /** Masks secrets in place; true if anything changed. */
    private static boolean redactJson(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(name);
                if (isSecretName(name)) {
                    if (!(value.isString() && value.asString().equals(MASK))) {
                        object.put(name, MASK);
                        changed = true;
                    }
                } else if (value.isString()) {
                    String redacted = redactText(value.asString());
                    if (!redacted.equals(value.asString())) {
                        object.set(name, StringNode.valueOf(redacted));
                        changed = true;
                    }
                } else {
                    changed |= redactJson(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isString()) {
                    String redacted = redactText(item.asString());
                    if (!redacted.equals(item.asString())) {
                        array.set(i, StringNode.valueOf(redacted));
                        changed = true;
                    }
                } else {
                    changed |= redactJson(item);
                }
            }
        }
        return changed;
    }

    private static boolean isSecretName(String name) {
        return SECRET_FIELD_NAMES.contains(name.toLowerCase(Locale.ROOT).replace('-', '_'));
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.strip();
        return trimmed.length() >= 2
                && (trimmed.charAt(0) == '{' && trimmed.charAt(trimmed.length() - 1) == '}'
                        || trimmed.charAt(0) == '[' && trimmed.charAt(trimmed.length() - 1) == ']');
    }
}
