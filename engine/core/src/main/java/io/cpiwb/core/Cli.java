package io.cpiwb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small helpers shared by the engines' command line front ends. */
public final class Cli {

    private Cli() {
    }

    /** Tiny argument parser: --key value, repeated keys, flags, positionals. */
    public static final class Args {
        private static final List<String> FLAGS = List.of("--json", "--stdio", "--fail-if-none", RpcServer.WORKER);
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
        return printTestResults(r, json, false);
    }

    /**
     * Exit code 0 when no case failed. No case file at all is a failure (a wrong path would pass otherwise);
     * with {@code failIfNone}, so is a run in which this engine ran no case (all were for other engines).
     */
    @SuppressWarnings("unchecked")
    public static int printTestResults(Map<String, Object> r, boolean json, boolean failIfNone) {
        int ran = ((Number) r.get("passed")).intValue() + ((Number) r.get("failed")).intValue();
        int code = printResults(r, json);
        if (code == 0 && Json.arr(r, "results").isEmpty()) {
            System.err.println("No test case (case.json) found.");
            return 1;
        }
        if (code == 0 && failIfNone && ran == 0) {
            System.err.println("No test case ran on this engine (--fail-if-none).");
            return 1;
        }
        return code;
    }

    /**
     * The {@code test} command of every engine: prints the results ({@code --json}, {@code --fail-if-none}) and,
     * with {@code --junit <file>}, also writes them as JUnit XML. The report changes neither the output nor the
     * exit code, unless it cannot be written: that is an error (1), and {@code --junit} without a file is 2.
     */
    public static int testCommand(Map<String, Object> r, Args a, String profile) {
        String junit = a.get("--junit");
        if (a.has("--junit") && (junit == null || junit.startsWith("-"))) {
            System.err.println("--junit needs a file: test <path> --junit <file>");
            return 2;
        }
        int code = printTestResults(r, a.has("--json"), a.has("--fail-if-none"));
        if (junit != null) {
            try {
                Path file = Path.of(junit).toAbsolutePath();
                Files.createDirectories(file.getParent());
                Files.writeString(file, junitXml(r, profile), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not write the JUnit report " + junit + ": " + e);
                return code == 0 ? 1 : code;
            }
        }
        return code;
    }

    /**
     * The results as a JUnit XML report: one {@code testsuite} named after the engine's profile, one
     * {@code testcase} per case. There is no official schema for this format; this is the layout Maven
     * Surefire writes, and its schema accepts these reports (docs/plan.md W5). Not tried with a CI reporter.
     * A failed case is a {@code failure}, an
     * error (or a status this method does not know) an {@code error}; a case of another engine is
     * {@code skipped}. The first message is the {@code message} attribute, all of them are the text.
     */
    @SuppressWarnings("unchecked")
    static String junitXml(Map<String, Object> r, String profile) {
        StringBuilder cases = new StringBuilder();
        int failures = 0, errors = 0, skipped = 0;
        long total = 0;
        List<Object> results = Json.arr(r, "results");
        for (Object o : results) {
            Map<String, Object> c = (Map<String, Object>) o;
            String status = String.valueOf(c.get("status"));
            long ms = c.get("ms") instanceof Number n ? n.longValue() : 0;
            total += ms;
            cases.append("  <testcase classname=\"").append(xml(profile, true)).append("\" name=\"")
                    .append(xml(String.valueOf(c.get("name")), true)).append("\" time=\"").append(seconds(ms)).append('"');
            if ("passed".equals(status)) {
                cases.append("/>\n");
                continue;
            }
            List<Object> messages = c.get("messages") instanceof List<?> l ? (List<Object>) l : List.of();
            String first = xml(messages.isEmpty() ? status : String.valueOf(messages.get(0)), true);
            if ("skipped".equals(status)) {
                skipped++;
                cases.append(">\n    <skipped message=\"").append(first).append("\"/>\n  </testcase>\n");
                continue;
            }
            String element = "failed".equals(status) ? "failure" : "error";
            if ("failed".equals(status)) {
                failures++;
            } else {
                errors++;
            }
            StringBuilder text = new StringBuilder();
            for (Object m : messages) {
                text.append(text.length() == 0 ? "" : "\n").append(m);
            }
            cases.append(">\n    <").append(element).append(" message=\"").append(first).append("\" type=\"")
                    .append(xml(status, true)).append("\">").append(xml(text.toString(), false))
                    .append("</").append(element).append(">\n  </testcase>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + xml(profile, true) + "\" tests=\"" + results.size()
                + "\" failures=\"" + failures + "\" errors=\"" + errors + "\" skipped=\"" + skipped
                + "\" time=\"" + seconds(total) + "\">\n" + cases + "</testsuite>\n";
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.3f", ms / 1000.0);
    }

    /**
     * Escapes text for XML 1.0. A character XML 1.0 cannot hold (most control characters, a lone surrogate)
     * becomes U+FFFD: an error message may quote any input, and the report must stay well-formed. In an
     * attribute, line breaks and tabs are written as references, or the parser would turn them into spaces.
     */
    static String xml(String s, boolean attribute) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\r' -> b.append("&#13;");
                case '\n', '\t' -> {
                    if (attribute) {
                        b.append("&#").append(c).append(';');
                    } else {
                        b.appendCodePoint(c);
                    }
                }
                default -> b.appendCodePoint(c < 0x20 || (c >= 0xD800 && c <= 0xDFFF) || c == 0xFFFE || c == 0xFFFF ? 0xFFFD : c);
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static int printResults(Map<String, Object> r, boolean json) {
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
