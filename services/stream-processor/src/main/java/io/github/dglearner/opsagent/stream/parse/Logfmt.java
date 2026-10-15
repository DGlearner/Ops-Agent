package io.github.dglearner.opsagent.stream.parse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code key=value key="quoted value"} pairs, as written by the application, Docker and node-exporter logs. */
final class Logfmt {

    private static final Pattern PAIR =
            Pattern.compile("([A-Za-z_][\\w.-]*)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s\"]*))");

    /** Pairs in order of appearance; values are untyped strings with quotes and escapes removed. */
    static Map<String, String> parse(String text) {
        Map<String, String> pairs = new LinkedHashMap<>();
        Matcher m = PAIR.matcher(text);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2).replaceAll("\\\\(.)", "$1") : m.group(3);
            pairs.put(m.group(1), value);
        }
        return pairs;
    }

    private Logfmt() {
    }
}
