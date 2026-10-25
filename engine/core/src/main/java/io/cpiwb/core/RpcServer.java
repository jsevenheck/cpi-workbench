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
                    .with("message", "Timeout: the call did not finish within " + timeout + " ms. The engine restarts.")
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

    public HttpServer serveHttp(int port, Path uiDir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/rpc", ex -> {
            try {
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
