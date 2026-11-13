package io.cpiwb.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Serves an {@link RpcHandler} as newline-delimited JSON over stdio (used by the desktop shell) or
 * over HTTP on localhost (browser/web mode and development).
 *
 * <p>Request: {@code {"id": 1, "method": "xslt.run", "params": {...}}}<br>
 * Response: {@code {"id": 1, "result": {...}}} or {@code {"id": 1, "error": {"message": "..."}}}
 */
public final class RpcServer {

    /** Exit code signalling the shell that the engine stopped after a timed-out call. */
    public static final int EXIT_TIMEOUT = 3;

    private final RpcHandler handler;
    private final long defaultTimeoutMs;
    /** Over HTTP the engine is started again after a timeout only by {@link #supervise}; over stdio the desktop shell does it. */
    private volatile boolean http;
    private volatile boolean supervised = true;

    /** Whether something starts this engine again after it exits with {@link #EXIT_TIMEOUT}. */
    public RpcServer supervised(boolean value) {
        supervised = value;
        return this;
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cpiwb-worker");
        t.setDaemon(true);
        return t;
    });

    public RpcServer(RpcHandler handler, long defaultTimeoutMs) {
        this.handler = handler;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /** Handles one request object and returns the response object. Never throws. */
    public Map<String, Object> handle(Map<String, Object> request) {
        Object id = request.get("id");
        String method = Json.str(request, "method", "");
        Map<String, Object> params = Json.obj(request, "params");
        long timeout = Json.num(params, "timeoutMs", defaultTimeoutMs);
        Future<Object> f = worker.submit(() -> handler.call(method, params == null ? Map.of() : params));
        try {
            Object result = f.get(timeout, TimeUnit.MILLISECONDS);
            return Json.o().with("id", id).with("result", result);
        } catch (TimeoutException e) {
            f.cancel(true);
            // A running transformation cannot be stopped safely; tell the caller and restart.
            scheduleExit();
            return Json.o().with("id", id).with("error", Json.o()
                    .with("message", "Timeout: the call did not finish within " + timeout + " ms. "
                            + (http && !supervised ? "The engine stops; start it again." : "The engine restarts."))
                    .with("timeout", true));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return Json.o().with("id", id).with("error", Json.o()
                    .with("message", cause.getMessage() == null ? cause.toString() : cause.getMessage())
                    .with("type", cause.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Json.o().with("id", id).with("error", Json.o().with("message", "Interrupted"));
        }
    }

    private static void scheduleExit() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // exiting anyway
            }
            System.exit(EXIT_TIMEOUT);
        });
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- stdio

    public void serveStdio() throws IOException {
        PrintStream protocolOut = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        // Anything else printed to stdout (libraries, user code) must not corrupt the protocol.
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        protocolOut.println(Json.write(Json.o().with("event", "ready").with("engine", handler.describe())));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> response;
            try {
                response = handle(Json.parseObject(line));
            } catch (IllegalArgumentException e) {
                response = Json.o().with("id", null).with("error", Json.o().with("message", "Bad request: " + e.getMessage()));
            }
            protocolOut.println(Json.write(response));
        }
    }

    // ---------------------------------------------------------------- http

    /** Marks the process that serves HTTP for {@link #supervise}; a command line without it supervises. */
    public static final String WORKER = "--worker";

    /**
     * Browser mode: runs the engine's HTTP server in a child process and starts it again when it exits after a
     * timed-out call (a running transformation or script cannot be stopped inside the process). Over stdio the
     * desktop shell does this. Returns the child's exit code, or -1 when no child can be started here; the
     * caller then serves by itself, and a timeout ends the engine.
     */
    public static int supervise(String[] args) throws InterruptedException {
        java.util.List<String> command = relaunchCommand(args);
        if (command == null) {
            return -1;
        }
        boolean first = true;
        while (true) {
            Process child;
            try {
                child = new ProcessBuilder(command).inheritIO().start();
            } catch (IOException e) {
                if (first) {
                    return -1;
                }
                System.err.println("Cannot start the engine again: " + e.getMessage());
                return 1;
            }
            first = false;
            // Ctrl+C or a kill of this process also ends the engine.
            Thread stop = new Thread(child::destroy);
            Runtime.getRuntime().addShutdownHook(stop);
            int code = child.waitFor();
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException e) {
                return code; // shutting down
            }
            if (code != EXIT_TIMEOUT) {
                return code;
            }
            System.err.println("The engine stopped after a timed-out call; starting it again.");
        }
    }

    /**
     * In the process that serves ({@link #WORKER}): exit when the supervising process is gone, so a supervisor
     * killed without its shutdown hooks (kill -9, Task Manager) leaves no engine holding the port.
     */
    public static void exitWithParent() {
        ProcessHandle.current().parent().ifPresent(p -> p.onExit().thenRun(() -> System.exit(0)));
    }

    /** This process's command line with {@link #WORKER} added, or null when it cannot be told. */
    static java.util.List<String> relaunchCommand(String[] args) {
        java.util.List<String> command = new java.util.ArrayList<>();
        // The command line as the system reports it, with the JVM options (e.g. -Xmx) the user gave; where the
        // system does not report it (Windows), it is rebuilt below without them.
        ProcessHandle.Info info = ProcessHandle.current().info();
        String[] own = info.arguments().orElse(new String[0]);
        if (info.command().isPresent() && own.length >= args.length
                && java.util.Arrays.equals(java.util.Arrays.copyOfRange(own, own.length - args.length, own.length), args)) {
            command.add(info.command().get());
            command.addAll(java.util.List.of(own));
            command.add(WORKER);
            return command;
        }
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String exe = ProcessHandle.current().info().command().orElse(null);
            if (exe == null) {
                return null;
            }
            command.add(exe);
        } else {
            String classPath = System.getProperty("java.class.path", "");
            String started = System.getProperty("sun.java.command", "");
            Path launcher = Path.of(System.getProperty("java.home", ""), "bin",
                    System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java");
            if (classPath.isEmpty() || started.isEmpty() || !Files.isRegularFile(launcher)) {
                return null;
            }
            command.add(launcher.toString());
            if (classPath.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") && started.startsWith(classPath)) {
                command.add("-jar"); // started with java -jar: the class path is the jar
                command.add(classPath);
            } else {
                command.add("-cp");
                command.add(classPath);
                command.add(started.split(" ")[0]);
            }
        }
        command.addAll(java.util.List.of(args));
        command.add(WORKER);
        return command;
    }

    public HttpServer serveHttp(int port, Path uiDir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http = true;
        server.createContext("/rpc", ex -> {
            try {
                // DNS rebinding: a foreign page whose name now resolves to 127.0.0.1 is same-origin for the
                // browser, so no preflight protects the engine. Such a request still names the foreign host.
                String origin = ex.getRequestHeaders().getFirst("Origin");
                if (!isLocalHost(ex.getRequestHeaders().getFirst("Host")) || origin != null && !isLocalOrigin(origin)) {
                    send(ex, 403, "text/plain", "Requests from this machine only".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                cors(ex);
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    send(ex, 204, "text/plain", new byte[0]);
                    return;
                }
                if (!"POST".equals(ex.getRequestMethod())) {
                    send(ex, 405, "text/plain", "POST only".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                // A custom header forces a CORS preflight, which only local origins pass. This keeps
                // other web pages from driving the engine (it can read and write project files).
                if (ex.getRequestHeaders().getFirst("X-Cpiwb") == null) {
                    send(ex, 403, "text/plain", "Missing X-Cpiwb header".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> response;
                try {
                    response = handle(Json.parseObject(body));
                } catch (IllegalArgumentException e) {
                    response = Json.o().with("error", Json.o().with("message", "Bad request: " + e.getMessage()));
                }
                send(ex, 200, "application/json", Json.write(response).getBytes(StandardCharsets.UTF_8));
            } finally {
                ex.close();
            }
        });
        server.createContext("/", ex -> {
            try {
                if (!isLocalHost(ex.getRequestHeaders().getFirst("Host"))) {
                    send(ex, 403, "text/plain", "Requests from this machine only".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                serveStatic(ex, uiDir);
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server;
    }

    private static void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && isLocalOrigin(origin)) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "content-type, x-cpiwb");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        }
    }

    /**
     * Pages served from this machine or by the desktop shell. The host is compared exactly, so
     * look-alikes such as {@code http://localhost.example.com} or {@code http://tauri.localhost.evil} fail.
     */
    static boolean isLocalOrigin(String origin) {
        try {
            URI u = new URI(origin);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (host == null || u.getRawPath() != null && !u.getRawPath().isEmpty() || u.getRawQuery() != null
                    || u.getRawUserInfo() != null) {
                return false;
            }
            if ("tauri".equals(scheme)) {
                return host.equals("localhost") && u.getPort() == -1;
            }
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            if (host.equals("tauri.localhost")) {
                return u.getPort() == -1;
            }
            return (host.equals("localhost") || host.equals("127.0.0.1")) && u.getPort() > 0;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** A Host header that names this machine: localhost, 127.0.0.1 or [::1], with or without a port. */
    static boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        String name = h.startsWith("[") ? (h.contains("]") ? h.substring(0, h.indexOf(']') + 1) : h)
                : (h.contains(":") ? h.substring(0, h.indexOf(':')) : h);
        String port = h.substring(name.length());
        return (name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]"))
                && (port.isEmpty() || port.matches(":\\d{1,5}"));
    }

    private static void serveStatic(HttpExchange ex, Path uiDir) throws IOException {
        if (uiDir == null) {
            send(ex, 404, "text/plain", "No UI directory configured (use --ui <dir>)".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        Path root = uiDir.toAbsolutePath().normalize();
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            file = root.resolve("index.html");
        }
        if (!Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            send(ex, 200, contentType(file.getFileName().toString()), in.readAllBytes());
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
