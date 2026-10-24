package io.cpiwb.groovy;

import io.cpiwb.core.Cli;
import io.cpiwb.core.Cli.Args;
import io.cpiwb.core.Json;
import io.cpiwb.core.RpcServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Command line entry point of a Groovy engine. The concrete engine class supplies the API version. */
public final class Main {

    private Main() {
    }

    public static void run(String[] args, GroovyEngine engine) throws Exception {
        String name = "groovy-1.x".equals(engine.describe().get("profile")) ? "cpiwb-groovy-v1" : "cpiwb-groovy-v2";
        String help = String.join("\n",
                name + " " + GroovyEngine.VERSION + " - CPI Workbench Groovy engine (" + engine.describe().get("profileLabel") + ")",
                "",
                "Usage:",
                "  " + name + " run --script <file> [--in <body file>] [--function processData]",
                "                 [-H name=value[:Type]]... [-P name=value[:Type]]... [--project <dir>] [--json]",
                "  " + name + " test <path> [--json]",
                "  " + name + " serve --stdio | --http <port> [--ui <dir>]",
                "  " + name + " info");
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println(help);
            return;
        }
        Args a = new Args(args);
        switch (args[0]) {
            case "info":
                System.out.println(Json.write(engine.describe()));
                break;
            case "serve":
                RpcServer server = new RpcServer(engine, a.num("--timeout", 30_000));
                if (a.has("--http")) {
                    server.serveHttp((int) a.num("--http", 7778), a.get("--ui") == null ? null : Path.of(a.get("--ui")));
                    System.err.println(name + " listening on http://127.0.0.1:" + a.num("--http", 7778));
                    Thread.currentThread().join();
                } else {
                    server.serveStdio();
                }
                break;
            case "run":
                System.exit(runScript(engine, a));
                break;
            case "test":
                String p = a.positional(1);
                System.exit(Cli.printTestResults(engine.runTests(Path.of(p == null ? "tests" : p)), a.has("--json")));
                break;
            default:
                System.err.println("Unknown command '" + args[0] + "'\n\n" + help);
                System.exit(2);
        }
    }

    @SuppressWarnings("unchecked")
    private static int runScript(GroovyEngine engine, Args a) throws Exception {
        Path script = Path.of(a.require("--script")).toAbsolutePath();
        Path root = a.get("--project") != null ? Path.of(a.get("--project")).toAbsolutePath()
                : io.cpiwb.core.TestRunner.projectRoot(script.getParent());
        Map<String, Object> req = Json.o()
                .with("script", Json.o().with("path", root.relativize(script).toString().replace('\\', '/')))
                .with("baseDir", root.toString())
                .with("function", a.get("--function") == null ? "processData" : a.get("--function"))
                .with("input", Json.o()
                        .with("body", a.get("--in") == null ? "" : Files.readString(Path.of(a.get("--in")), StandardCharsets.UTF_8))
                        .with("headers", Cli.typed(a.all("-H")))
                        .with("properties", Cli.typed(a.all("-P"))));
        Map<String, Object> r = (Map<String, Object>) engine.call("groovy.run", req);
        if (a.has("--json")) {
            System.out.println(Json.write(r));
        } else {
            Cli.printDiagnostics(r);
            String console = Json.str(r, "console", "");
            if (!console.isEmpty()) {
                System.err.print(console.endsWith("\n") ? console : console + "\n");
            }
            if (Json.bool(r, "ok", false)) {
                Map<String, Object> out = Json.obj(r, "output");
                System.out.println(Json.str(out, "body", ""));
                for (Object h : Json.arr(out, "headers")) {
                    Map<String, Object> m = (Map<String, Object>) h;
                    System.err.println("[header] " + m.get("name") + " = " + m.get("value") + " (" + m.get("type") + ")");
                }
                for (Object h : Json.arr(out, "properties")) {
                    Map<String, Object> m = (Map<String, Object>) h;
                    System.err.println("[property] " + m.get("name") + " = " + m.get("value") + " (" + m.get("type") + ")");
                }
            } else if (r.get("stackTrace") != null) {
                System.err.print(r.get("stackTrace"));
            }
        }
        return Json.bool(r, "ok", false) ? 0 : 1;
    }
}
