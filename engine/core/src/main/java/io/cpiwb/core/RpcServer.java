package io.cpiwb.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Serves an {@link RpcHandler} as newline-delimited JSON over stdio (used by the desktop shell) or
 * over HTTP on localhost (browser/web mode and development).
 *
 * <p>Request: {@code {"id": 1, "method": "xslt.run", "params": {...}}}<br>
 * Response: {@code {"id": 1, "result": {...}}} or {@code {"id": 1, "error": {"message": "..."}}}
 * Browser requests to {@code /project/rpc} also carry a top-level {@code projectRoot} (absolute path or null).
 * Desktop stdio calls wrap that same request in {@code project.request}; plain stdio calls remain stateful.
 */
public final class RpcServer {

    /** Exit code signalling the shell that the engine stopped after a timed-out call. */
    public static final int EXIT_TIMEOUT = 3;

    private static final int HTTP_CONNECTIONS = 24;
    private static final int HTTP_THREADS = 12;
    private static final int HTTP_IN_FLIGHT = 4;
    private static final int HTTP_BODY_LIMIT = 32 * 1024 * 1024;
    /** Beyond the execution timeout: a file save during a long run waits for it instead of failing as busy. */
    private static final long HTTP_QUEUE_MARGIN_MS = 5_000;

    private final RpcHandler handler;
    private final long defaultTimeoutMs;
    private final int bodyLimit;
    final long queueMs;
    // Admission covers uploads, queueing, executions and RPC replies. Leave HTTP threads for preflights,
    // static assets and overload replies. Only the holder of httpExecution can submit to the worker over HTTP.
    private final Semaphore httpAdmission = new Semaphore(HTTP_IN_FLIGHT);
    private final Semaphore httpExecution = new Semaphore(1, true);
    /** Over HTTP the engine is started again after a timeout only by {@link #supervise}; over stdio the desktop shell does it. */
    private volatile boolean http;
    private volatile boolean supervised = true;
    private volatile boolean retiring;

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
        this(handler, defaultTimeoutMs, HTTP_BODY_LIMIT, defaultTimeoutMs + HTTP_QUEUE_MARGIN_MS);
    }

    RpcServer(RpcHandler handler, long defaultTimeoutMs, int bodyLimit, long queueMs) {
        this.handler = handler;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.bodyLimit = bodyLimit;
        this.queueMs = queueMs;
    }

    /** Handles one request object and returns the response object. Never throws. */
    public Map<String, Object> handle(Map<String, Object> request) {
        if (!"project.request".equals(request.get("method"))) {
            return handle(request, false);
        }
        Map<String, Object> scoped = Json.obj(request, "params");
        if (scoped == null) {
            return Json.o().with("id", request.get("id"))
                    .with("error", Json.o().with("message", "project.request expects a scoped request object"));
        }
        // Use the outer correlation ID and the inner method's parameters (including its timeout).
        Json.Obj inner = Json.o();
        inner.putAll(scoped);
        return handle(inner.with("id", request.get("id")), true);
    }

    private Map<String, Object> handle(Map<String, Object> request, boolean scoped) {
        Object id = request.get("id");
        if (retiring) {
            return error(id, "The engine is stopping after a timed-out call. Try again once it is ready.");
        }
        String method = Json.str(request, "method", "");
        Map<String, Object> params = Json.obj(request, "params");
        long timeout = Json.num(params, "timeoutMs", defaultTimeoutMs);
        Map<String, Object> args = params == null ? Map.of() : params;
        Future<Object> f = worker.submit(() -> {
            if (!scoped) {
                return handler.call(method, args);
            }
            Object root = request.get("projectRoot");
            if (!request.containsKey("projectRoot") || (root != null && !(root instanceof String))) {
                throw new IllegalArgumentException("Scoped RPC requires projectRoot (an absolute path or null)");
            }
            if (args.containsKey("baseDir")) {
                throw new IllegalArgumentException("Scoped RPC uses projectRoot, not a baseDir override");
            }
            return handler.call(method, args, ProjectFiles.scoped((String) root));
        });
        try {
            Object result = f.get(timeout, TimeUnit.MILLISECONDS);
            return Json.o().with("id", id).with("result", result);
        } catch (TimeoutException e) {
            // A running transformation cannot be stopped safely. Do not admit another call before exit.
            retire(f);
            return Json.o().with("id", id).with("error", Json.o()
                    .with("message", "Timeout: the call did not finish within " + timeout + " ms. "
                            + (http && !supervised ? "The engine stops; start it again." : "The engine restarts."))
                    .with("timeout", true));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // A missing file's exception message is the bare path; say what it means.
            String message = cause instanceof java.nio.file.NoSuchFileException ? "No such file: " + cause.getMessage()
                    : cause.getMessage() == null ? cause.toString() : cause.getMessage();
            return Json.o().with("id", id).with("error", Json.o()
                    .with("message", message)
                    .with("type", cause.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            retire(f);
            Thread.currentThread().interrupt();
            return error(id, "Interrupted");
        }
    }

    private void retire(Future<?> active) {
        retiring = true;
        active.cancel(true);
        scheduleExit();
    }

    private static Map<String, Object> error(Object id, String message) {
        return Json.o().with("id", id).with("error", Json.o().with("message", message));
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
        // The JDK server snapshots these at first use. Its request timer covers both headers and body and
        // closes the socket to unblock reads. OpenJDK 17u/21u/25u use seconds for maxReqTime (timerMillis is ms).
        // Defaults only: a value the user passed with -D stays.
        defaultProperty("jdk.httpserver.maxConnections", Integer.toString(HTTP_CONNECTIONS));
        defaultProperty("sun.net.httpserver.maxIdleConnections", "8");
        defaultProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
        defaultProperty("sun.net.httpserver.maxReqTime", "10");
        defaultProperty("sun.net.httpserver.timerMillis", "250");
        // Do not drain an unread upload on close after a rejection: the connection will be closed instead.
        defaultProperty("sun.net.httpserver.drainAmount", "0");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), HTTP_CONNECTIONS);
        http = true;
        server.createContext("/rpc", rpcHandler(false)); // compatibility for local RPC clients
        server.createContext("/project/rpc", rpcHandler(true));
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
        ThreadPoolExecutor executor = new ThreadPoolExecutor(HTTP_THREADS, HTTP_THREADS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(HTTP_CONNECTIONS), r -> {
                    Thread t = new Thread(r, "cpiwb-http");
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(true);
        server.setExecutor(executor); // on transport saturation the JDK closes the rejected socket
        server.start();
        return server;
    }

    /** Sets a system property unless it is set already (for example with -D on the command line). */
    static void defaultProperty(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** The declared body length, null when none is declared, or -1 when the header is no non-negative number. */
    static Long declaredLength(String header) {
        if (header == null) {
            return null;
        }
        try {
            long n = Long.parseLong(header.trim());
            return n < 0 ? -1L : n;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private HttpHandler rpcHandler(boolean scoped) {
        return ex -> {
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
                Long declaredLength = declaredLength(ex.getRequestHeaders().getFirst("Content-Length"));
                if (declaredLength != null && declaredLength < 0) {
                    rejectHttp(ex, 400, null, "Bad request: Content-Length is not a non-negative number.");
                    return;
                }
                if (declaredLength != null && declaredLength > bodyLimit) {
                    rejectHttp(ex, 413, null, bodyLimitMessage());
                    return;
                }
                if (!httpAdmission.tryAcquire()) {
                    rejectHttp(ex, 503, null, "Engine busy: too many HTTP requests in progress. Try again shortly.");
                    return;
                }
                try {
                    handleHttpRpc(ex, scoped);
                } finally {
                    httpAdmission.release();
                }
            } finally {
                ex.close();
            }
        };
    }

    private String bodyLimitMessage() {
        return "UnsupportedInSimulator: the browser RPC request exceeds " + bodyLimit
                + " bytes (including JSON). Use a smaller message or run it in the desktop app.";
    }

    private void handleHttpRpc(HttpExchange ex, boolean scoped) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(bodyLimit + 1);
        if (body.length > bodyLimit) {
            rejectHttp(ex, 413, null, bodyLimitMessage());
            return;
        }
        Map<String, Object> request;
        try {
            request = Json.parseObject(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error(null, "Bad request: " + e.getMessage()));
            return;
        }
        Object id = request.get("id");
        Map<String, Object> response;
        try {
            if (!httpExecution.tryAcquire(queueMs, TimeUnit.MILLISECONDS)) {
                rejectHttp(ex, 503, id, "Engine busy: timed out waiting for a turn. The request did not run. Try again shortly.");
                return;
            }
            try {
                response = handle(request, scoped); // the execution timeout starts here, not during admission
            } finally {
                httpExecution.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejectHttp(ex, 503, id, "Interrupted while waiting for the engine. The request did not run.");
            return;
        }
        sendJson(ex, 200, response);
    }

    private static void rejectHttp(HttpExchange ex, int status, Object id, String message) throws IOException {
        ex.getResponseHeaders().set("Connection", "close");
        if (status == 503) {
            ex.getResponseHeaders().set("Retry-After", "1");
        }
        sendJson(ex, status, error(id, message));
    }

    private static void sendJson(HttpExchange ex, int status, Map<String, Object> response) throws IOException {
        send(ex, status, "application/json", Json.write(response).getBytes(StandardCharsets.UTF_8));
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
