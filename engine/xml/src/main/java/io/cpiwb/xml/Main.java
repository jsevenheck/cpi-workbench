package io.cpiwb.xml;

import io.cpiwb.core.Cli;
import io.cpiwb.core.Cli.Args;
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
        in.put("headers", Cli.typed(a.all("-H")));
        in.put("properties", Cli.typed(a.all("-P")));
        return in;
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
            Cli.printDiagnostics(r);
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
            Cli.printDiagnostics(r);
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
            Cli.printDiagnostics(r);
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
        return Cli.printTestResults(r, a.has("--json"));
    }

}
