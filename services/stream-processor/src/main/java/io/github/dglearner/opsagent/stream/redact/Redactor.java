package io.github.dglearner.opsagent.stream.redact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masks credentials before events leave stream-processor, so neither storage nor an LLM sees them. Applied to the
 * message and to every string field; fields whose name is a secret are masked as a whole.
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

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            // Authorization: Bearer abc / authorization=Basic abc
            new Rule(Pattern.compile("(?i)(authorization\\s*[:=]\\s*\"?)(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]+"),
                    "$1$2 " + MASK),
            // password=..., "token": "...", api_key: ...
            new Rule(Pattern.compile("(?i)(\"?\\b(?:" + SECRET_KEYS + ")\\b\"?\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)"),
                    "$1" + MASK),
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
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    public Map<String, Object> redactFields(Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            if (SECRET_FIELD_NAMES.contains(name.toLowerCase(Locale.ROOT).replace('-', '_'))) {
                result.put(name, MASK);
            } else if (value instanceof String s) {
                result.put(name, redact(s));
            } else {
                result.put(name, value);
            }
        });
        return result;
    }
}
