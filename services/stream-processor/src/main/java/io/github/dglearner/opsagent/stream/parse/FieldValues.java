package io.github.dglearner.opsagent.stream.parse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Turns parsed strings into JSON-friendly numbers and booleans where that is unambiguous. */
final class FieldValues {

    // No leading zeros, so ids such as "007" stay strings.
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9]\\d{0,17})");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9]\\d*)\\.\\d+");

    static Object typed(String value) {
        if (value == null) {
            return null;
        }
        if (INTEGER.matcher(value).matches()) {
            return Long.parseLong(value);
        }
        if (DECIMAL.matcher(value).matches()) {
            return Double.parseDouble(value);
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    static Map<String, Object> typed(Map<String, String> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((k, v) -> result.put(k, typed(v)));
        return result;
    }

    static long asLong(Object value, long fallback) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().replaceAll("[a-zA-Z]+$", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private FieldValues() {
    }
}
