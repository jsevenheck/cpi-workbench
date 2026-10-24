package io.cpiwb.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small helpers shared by the engines' command line front ends. */
public final class Cli {

    private Cli() {
    }

    /** Tiny argument parser: --key value, repeated keys, flags, positionals. */
    public static final class Args {
        private static final List<String> FLAGS = List.of("--json", "--stdio");
        private final String[] args;

        public Args(String[] args) {
            this.args = args;
        }

        public boolean has(String key) {
            for (String s : args) {
                if (s.equals(key)) {
                    return true;
                }
            }
            return false;
        }

        public String get(String key) {
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals(key)) {
                    return args[i + 1];
                }
            }
            return null;
        }

        public String require(String key) {
            String v = get(key);
            if (v == null) {
                throw new IllegalArgumentException("Missing " + key);
            }
            return v;
        }

        public long num(String key, long def) {
            String v = get(key);
            return v == null || v.startsWith("--") ? def : Long.parseLong(v);
        }

        public List<String> all(String key) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals(key)) {
                    out.add(args[i + 1]);
                }
            }
            return out;
        }

        public String positional(int index) {
            int n = 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("-")) {
                    if (i + 1 < args.length && !FLAGS.contains(args[i])) {
                        i++;
                    }
                    continue;
                }
                if (n++ == index) {
                    return args[i];
                }
            }
            return null;
        }
    }

    /** Parses {@code name=value[:Type]} specs from -H / -P options. */
    public static List<Object> typed(List<String> specs) {
        List<Object> out = new ArrayList<>();
        for (String s : specs) {
            int eq = s.indexOf('=');
            if (eq < 1) {
                throw new IllegalArgumentException("Expected name=value but got '" + s + "'");
            }
            String name = s.substring(0, eq);
            String value = s.substring(eq + 1);
            String type = "String";
            int colon = value.lastIndexOf(':');
            if (colon > 0 && TypedValue.TYPES.contains(value.substring(colon + 1))) {
                type = value.substring(colon + 1);
                value = value.substring(0, colon);
            }
            out.add(Json.o().with("name", name).with("value", value).with("type", type));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void printDiagnostics(Map<String, Object> r) {
        for (Object d : Json.arr(r, "diagnostics")) {
            Map<String, Object> m = (Map<String, Object>) d;
            StringBuilder sb = new StringBuilder(String.valueOf(m.get("severity")).toUpperCase());
            if (m.get("line") != null) {
                sb.append(" line ").append(m.get("line"));
                if (m.get("column") != null) {
                    sb.append(':').append(m.get("column"));
                }
            }
            if (m.get("code") != null) {
                sb.append(" ").append(m.get("code"));
            }
            System.err.println(sb.append(": ").append(m.get("message")));
        }
    }

    @SuppressWarnings("unchecked")
    public static int printTestResults(Map<String, Object> r, boolean json) {
        if (json) {
            System.out.println(Json.write(r));
        } else {
            for (Object o : Json.arr(r, "results")) {
                Map<String, Object> c = (Map<String, Object>) o;
                System.out.printf("%-8s %s (%s ms)%n", c.get("status").toString().toUpperCase(), c.get("name"), c.get("ms"));
                for (Object m : (List<Object>) c.get("messages")) {
                    System.out.println("         " + m);
                }
            }
            System.out.printf("%n%s passed, %s failed, %s skipped%n", r.get("passed"), r.get("failed"), r.get("skipped"));
        }
        return ((Number) r.get("failed")).intValue() == 0 ? 0 : 1;
    }
}
