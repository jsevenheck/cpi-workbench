package io.cpiwb.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between the typed header/property notation used in test cases and the UI
 * ({@code {"name": "x", "value": "1", "type": "Integer"}}) and real Java objects, which is what
 * CPI keeps in the Camel exchange.
 */
public final class TypedValue {

    /** Type names offered in the UI, in display order. */
    public static final List<String> TYPES = List.of(
            "String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", "Date", "byte[]", "null");

    private TypedValue() {
    }

    public static Object toJava(String type, Object raw) {
        String t = type == null || type.isBlank() ? "String" : type.trim();
        if ("null".equals(t)) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        String s = raw.toString();
        try {
            switch (t) {
                case "String":
                case "java.lang.String":
                    return s;
                case "Integer":
                case "java.lang.Integer":
                    return Integer.valueOf(s.trim());
                case "Long":
                case "java.lang.Long":
                    return Long.valueOf(s.trim());
                case "Boolean":
                case "java.lang.Boolean":
                    return Boolean.valueOf(s.trim());
                case "Double":
                case "java.lang.Double":
                    return Double.valueOf(s.trim());
                case "Float":
                case "java.lang.Float":
                    return Float.valueOf(s.trim());
                case "BigDecimal":
                case "java.math.BigDecimal":
                    return new BigDecimal(s.trim());
                case "Date":
                case "java.util.Date":
                    return parseDate(s.trim());
                case "byte[]":
                    return Base64.getDecoder().decode(s.trim());
                default:
                    throw new IllegalArgumentException("Unsupported type '" + t + "'. Supported: " + TYPES);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value '" + s + "' is not a valid " + t, e);
        }
    }

    private static Date parseDate(String s) {
        try {
            return Date.from(OffsetDateTime.parse(s).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Date.from(Instant.parse(s));
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Date value '" + s + "' must be ISO-8601, e.g. 2026-01-31T12:00:00Z");
            }
        }
    }

    public static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[]) {
            return "byte[]";
        }
        Class<?> c = value.getClass();
        if (c.getName().startsWith("java.lang.") || c == BigDecimal.class || c == Date.class) {
            return c.getSimpleName();
        }
        return c.getName();
    }

    public static String display(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        if (value instanceof Date) {
            return ((Date) value).toInstant().toString();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return value.toString();
    }

    /**
     * Reads headers or properties. Accepts a list of {@code {name, value, type}} objects or the
     * shorthand map form {@code {"name": "value"}} / {@code {"name": {"value": .., "type": ..}}}.
     */
    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, Object> readMap(Object json) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        if (json instanceof List) {
            for (Object o : (List<Object>) json) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) o;
                String name = Json.str(m, "name", "").trim();
                if (name.isEmpty() || Json.bool(m, "disabled", false)) {
                    continue;
                }
                out.put(name, toJava(Json.str(m, "type", "String"), m.get("value")));
            }
        } else if (json instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) json).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) v;
                    out.put(e.getKey(), toJava(Json.str(m, "type", "String"), m.get("value")));
                } else if (v instanceof Boolean) {
                    out.put(e.getKey(), v);
                } else {
                    out.put(e.getKey(), v == null ? null : v.toString());
                }
            }
        } else {
            throw new IllegalArgumentException("Headers/properties must be a list or an object");
        }
        return out;
    }

    public static List<Object> writeList(Map<String, Object> values) {
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            list.add(Json.o()
                    .with("name", e.getKey())
                    .with("value", display(e.getValue()))
                    .with("type", typeOf(e.getValue())));
        }
        return list;
    }
}
