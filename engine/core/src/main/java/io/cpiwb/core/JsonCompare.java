package io.cpiwb.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code "compare": "json"} of a test case: both bodies parsed as JSON and compared as values. Members of an object
 * may stand in any order, numbers are equal when their values are (1 and 1.0), arrays keep their order.
 * {@code expect.ignore} is then a list of JSON Pointers (RFC 6901) whose values are removed from both documents
 * first; a segment {@code *} stands for every member or element. This is CPI Workbench's own rule for its test
 * cases; it imitates nothing in CPI.
 */
final class JsonCompare {

    private JsonCompare() {
    }

    /** Null when equal, else where they differ first. */
    static String compare(String expected, String actual, List<String> ignore) {
        Object e;
        Object a;
        try {
            e = Json.parse(expected);
            a = Json.parse(actual);
        } catch (RuntimeException ex) {
            return "cannot compare as JSON (" + ex.getMessage() + "); use \"compare\": \"text\"";
        }
        List<String> unmatched = new ArrayList<>();
        for (String pointer : ignore) {
            List<String> path = parse(pointer);
            if (remove(e, path, 0) + remove(a, path, 0) == 0) {
                unmatched.add(pointer);
            }
        }
        prune(e);
        prune(a);
        String diff = diff(e, a, "");
        if (diff == null) {
            return null;
        }
        return unmatched.isEmpty() ? diff : diff + " (expect.ignore matched nothing: " + String.join(", ", unmatched) + ")";
    }

    /** The segments of a JSON Pointer; the whole document ("") cannot be left out. */
    static List<String> parse(String pointer) {
        if (pointer.isEmpty()) {
            throw new IllegalArgumentException("expect.ignore '' selects the whole document; nothing would be compared");
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("expect.ignore '" + pointer + "' is no JSON Pointer: with \"compare\": \"json\" "
                    + "the rules are JSON Pointers such as /order/createdAt or /items/*/id");
        }
        List<String> out = new ArrayList<>();
        for (String s : pointer.substring(1).split("/", -1)) {
            out.add(s.replace("~1", "/").replace("~0", "~"));
        }
        return out;
    }

    /** Stands for a removed array element until every rule is applied: the rules index the documents as written. */
    private static final Object REMOVED = new Object();

    @SuppressWarnings("unchecked")
    private static void prune(Object node) {
        if (node instanceof List) {
            ((List<Object>) node).removeIf(v -> v == REMOVED);
            ((List<Object>) node).forEach(JsonCompare::prune);
        } else if (node instanceof Map) {
            ((Map<String, Object>) node).values().forEach(JsonCompare::prune);
        }
    }

    /** Removes what {@code path} selects below {@code node}; returns how many values it removed. */
    @SuppressWarnings("unchecked")
    private static int remove(Object node, List<String> path, int i) {
        String seg = path.get(i);
        boolean last = i == path.size() - 1;
        boolean any = "*".equals(seg);
        int n = 0;
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            if (last) {
                if (any) {
                    n = m.size();
                    m.clear();
                } else if (m.containsKey(seg)) {
                    m.remove(seg);
                    n = 1;
                }
            } else if (any) {
                for (Object v : m.values()) {
                    n += remove(v, path, i + 1);
                }
            } else if (m.containsKey(seg)) {
                n = remove(m.get(seg), path, i + 1);
            }
        } else if (node instanceof List) {
            List<Object> l = (List<Object>) node;
            int index = index(seg);
            if (last) {
                if (any) {
                    n = l.size();
                    l.clear();
                } else if (index >= 0 && index < l.size() && l.get(index) != REMOVED) {
                    l.set(index, REMOVED);
                    n = 1;
                }
            } else if (any) {
                for (Object v : l) {
                    n += remove(v, path, i + 1);
                }
            } else if (index >= 0 && index < l.size()) {
                n = remove(l.get(index), path, i + 1);
            }
        }
        return n;
    }

    private static int index(String seg) {
        if (seg.isEmpty() || seg.length() > 9 || !seg.chars().allMatch(Character::isDigit) || (seg.length() > 1 && seg.charAt(0) == '0')) {
            return -1;
        }
        return Integer.parseInt(seg);
    }

    @SuppressWarnings("unchecked")
    private static String diff(Object e, Object a, String at) {
        String where = at.isEmpty() ? "/" : at;
        if (e instanceof Map && a instanceof Map) {
            Map<String, Object> em = (Map<String, Object>) e;
            Map<String, Object> am = (Map<String, Object>) a;
            for (Map.Entry<String, Object> x : em.entrySet()) {
                if (!am.containsKey(x.getKey())) {
                    return "at " + pointer(at, x.getKey()) + ": expected " + show(x.getValue()) + " but the member is missing";
                }
                String d = diff(x.getValue(), am.get(x.getKey()), pointer(at, x.getKey()));
                if (d != null) {
                    return d;
                }
            }
            for (String k : am.keySet()) {
                if (!em.containsKey(k)) {
                    return "at " + pointer(at, k) + ": unexpected member " + show(am.get(k));
                }
            }
            return null;
        }
        if (e instanceof List && a instanceof List) {
            List<Object> el = (List<Object>) e;
            List<Object> al = (List<Object>) a;
            for (int i = 0; i < Math.min(el.size(), al.size()); i++) {
                String d = diff(el.get(i), al.get(i), at + "/" + i);
                if (d != null) {
                    return d;
                }
            }
            return el.size() == al.size() ? null
                    : "at " + where + ": expected " + el.size() + " elements but there are " + al.size();
        }
        if (e instanceof Number && a instanceof Number) {
            return new BigDecimal(e.toString()).compareTo(new BigDecimal(a.toString())) == 0 ? null
                    : "at " + where + ": expected " + show(e) + " but was " + show(a);
        }
        return Objects.equals(e, a) ? null : "at " + where + ": expected " + show(e) + " but was " + show(a);
    }

    private static String pointer(String at, String key) {
        return at + "/" + key.replace("~", "~0").replace("/", "~1");
    }

    private static String show(Object v) {
        String s = Json.write(v);
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
