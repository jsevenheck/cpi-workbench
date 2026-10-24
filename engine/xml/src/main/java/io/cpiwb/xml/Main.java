package io.cpiwb.xml;

import io.cpiwb.core.Json;
import io.cpiwb.core.RpcServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command line entry point of the XML engine. Run without arguments for help. */
public final class Main {

    private static final String HELP = String.join("\n",
            "cpiwb-xml " + XmlEngine.VERSION + " - CPI Workbench XML engine",
            "",
            "Usage:",
            "  cpiwb-xml xslt --xsl <file> --in <file> [-H name=value[:Type]]... [-P name=value[:Type]]...",
            "                 [--project <dir>] [--out <file>] [--json]",
            "  cpiwb-xml validate --xsd <file> --in <file> [--project <dir>] [--json]",
            "  cpiwb-xml xpath --expr <xpath> --in <file> [--version 1.0|3.1] [--ns prefix=uri]... [--json]",
            "  cpiwb-xml test <path> [--json]          run test cases (case.json) below <path>",
            "  cpiwb-xml serve --stdio                 JSON-RPC over stdin/stdout (desktop app)",
            "  cpiwb-xml serve --http <port> [--ui <dir>]   JSON-RPC + UI on http://127.0.0.1:<port>",
            "  cpiwb-xml info",
            "",
            "Header/property types: String (default), Integer, Long, Boolean, Double, Float, BigDecimal, Date, byte[]");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println(HELP);
            return;
        }
        Args a = new Args(args);
        XmlEngine engine = new XmlEngine();
        switch (args[0]) {
            case "info":
                System.out.println(Json.write(engine.describe()));
                break;
            case "serve":
                serve(engine, a);
                break;
            case "xslt":
                System.exit(xslt(engine, a));
                break;
            case "validate":
                System.exit(validate(engine, a));
                break;
            case "xpath":
                System.exit(xpath(engine, a));
                break;
            case "test":
                System.exit(test(engine, a));
                break;
            default:
                System.err.println("Unknown command '" + args[0] + "'\n\n" + HELP);
                System.exit(2);
        }
    }

    private static void serve(XmlEngine engine, Args a) throws Exception {
        RpcServer server = new RpcServer(engine, a.num("--timeout", 30_000));
        if (a.has("--http")) {
            int port = (int) a.num("--http", 7777);
            Path ui = a.get("--ui") == null ? null : Path.of(a.get("--ui"));
            server.serveHttp(port, ui);
            System.err.println("cpiwb-xml listening on http://127.0.0.1:" + port + (ui == null ? " (RPC only)" : ""));
            Thread.currentThread().join();
        } else {
            server.serveStdio();
        }
    }

    private static Map<String, Object> input(Args a) throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("body", a.get("--in") == null ? "" : Files.readString(Path.of(a.get("--in")), StandardCharsets.UTF_8));
        in.put("headers", typed(a.all("-H")));
        in.put("properties", typed(a.all("-P")));
        return in;
    }

    private static List<Object> typed(List<String> specs) {
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
            if (colon > 0 && io.cpiwb.core.TypedValue.TYPES.contains(value.substring(colon + 1))) {
                type = value.substring(colon + 1);
                value = value.substring(0, colon);
            }
            out.add(Json.o().with("name", name).with("value", value).with("type", type));
        }
        return out;
    }

    private static Path project(Args a, Path file) {
        if (a.get("--project") != null) {
            return Path.of(a.get("--project")).toAbsolutePath();
        }
        return io.cpiwb.core.TestRunner.projectRoot(file.toAbsolutePath().getParent());
    }

    @SuppressWarnings("unchecked")
    private static int xslt(XmlEngine engine, Args a) throws Exception {
        Path xsl = Path.of(a.require("--xsl")).toAbsolutePath();
        Path root = project(a, xsl);
        Map<String, Object> req = Json.o()
                .with("xslt", Json.o().with("path", root.relativize(xsl).toString().replace('\\', '/')))
                .with("baseDir", root.toString())
                .with("input", input(a));
        Map<String, Object> r = (Map<String, Object>) engine.call("xslt.run", req);
        if (a.has("--json")) {
            System.out.println(Json.write(r));
        } else {
            printDiagnostics(r);
            for (Object m : Json.arr(r, "messages")) {
                System.err.println("[xsl:message] " + ((Map<String, Object>) m).get("text"));
            }
            if (Json.bool(r, "ok", false)) {
                Map<String, Object> out = Json.obj(r, "output");
                String body = Json.str(out, "body", "");
                if (a.get("--out") != null) {
                    Files.writeString(Path.of(a.get("--out")), body, StandardCharsets.UTF_8);
                } else {
                    System.out.println(body);
                }
                for (Object c : Json.arr(r, "cpiCalls")) {
                    Map<String, Object> m = (Map<String, Object>) c;
                    System.err.println("[" + m.get("function") + "] " + m.get("name") + " = " + m.get("value"));
                }
            }
        }
        return Json.bool(r, "ok", false) ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static int validate(XmlEngine engine, Args a) throws Exception {
        Path xsd = Path.of(a.require("--xsd")).toAbsolutePath();
        Path root = project(a, xsd);
        Map<String, Object> req = Json.o()
                .with("schema", Json.o().with("path", root.relativize(xsd).toString().replace('\\', '/')))
                .with("baseDir", root.toString())
                .with("input", input(a));
        Map<String, Object> r = (Map<String, Object>) engine.call("xsd.validate", req);
        if (a.has("--json")) {
            System.out.println(Json.write(r));
        } else {
            printDiagnostics(r);
            System.out.println(Json.bool(r, "valid", false) ? "valid" : "INVALID");
        }
        return Json.bool(r, "valid", false) ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static int xpath(XmlEngine engine, Args a) throws Exception {
        Map<String, Object> ns = new LinkedHashMap<>();
        for (String s : a.all("--ns")) {
            int eq = s.indexOf('=');
            ns.put(s.substring(0, eq), s.substring(eq + 1));
        }
        Map<String, Object> req = Json.o().with("expression", a.require("--expr"))
                .with("version", a.get("--version") == null ? "1.0" : a.get("--version"))
                .with("namespaces", ns).with("input", input(a));
        Map<String, Object> r = (Map<String, Object>) engine.call("xpath.eval", req);
        if (a.has("--json")) {
            System.out.println(Json.write(r));
        } else {
            printDiagnostics(r);
            for (Object o : Json.arr(r, "items")) {
                System.out.println(((Map<String, Object>) o).get("value"));
            }
        }
        return Json.bool(r, "ok", false) ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static int test(XmlEngine engine, Args a) throws Exception {
        String path = a.positional(1);
        Map<String, Object> r = engine.runTests(Path.of(path == null ? "tests" : path));
        if (a.has("--json")) {
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

    @SuppressWarnings("unchecked")
    private static void printDiagnostics(Map<String, Object> r) {
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

    /** Tiny argument parser: --key value, repeated keys, positionals. */
    private static final class Args {
        private final String[] args;

        Args(String[] args) {
            this.args = args;
        }

        boolean has(String key) {
            for (String s : args) {
                if (s.equals(key)) {
                    return true;
                }
            }
            return false;
        }

        String get(String key) {
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals(key)) {
                    return args[i + 1];
                }
            }
            return null;
        }

        String require(String key) {
            String v = get(key);
            if (v == null) {
                throw new IllegalArgumentException("Missing " + key);
            }
            return v;
        }

        long num(String key, long def) {
            String v = get(key);
            return v == null || v.startsWith("--") ? def : Long.parseLong(v);
        }

        List<String> all(String key) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals(key)) {
                    out.add(args[i + 1]);
                }
            }
            return out;
        }

        String positional(int index) {
            int n = 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("-")) {
                    if (i + 1 < args.length && !args[i].equals("--json") && !args[i].equals("--stdio")) {
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
}
