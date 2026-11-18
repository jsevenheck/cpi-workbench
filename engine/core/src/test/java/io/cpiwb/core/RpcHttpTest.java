package io.cpiwb.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcHttpTest {
    private static final String PING = "{\"id\":1,\"method\":\"ping\",\"projectRoot\":null}";
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private HttpServer serve(int bodyLimit, long queueMs) throws Exception {
        RpcHandler handler = new RpcHandler() {
            public Object call(String method, Map<String, Object> args, ProjectFiles project) throws Exception {
                return call(method, args);
            }

            public Object call(String method, Map<String, Object> args) throws Exception {
                calls.incrementAndGet();
                if ("hold".equals(method)) {
                    started.countDown();
                    if (!release.await(8, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the handler");
                    }
                }
                return method;
            }

            public Map<String, Object> describe() {
                return Json.o();
            }
        };
        return new RpcServer(handler, 10_000, bodyLimit, queueMs).serveHttp(0, null);
    }

    @Test
    void queuedExpiryDoesNotRunOrInterruptTheActiveCall() throws Exception {
        HttpServer server = serve(1024, 250);
        try {
            var running = client.sendAsync(post(server, "/project/rpc", "{\"method\":\"hold\",\"projectRoot\":null}"),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var waiting = send(post(server, "/rpc", "{\"id\":42,\"method\":\"ping\",\"params\":{\"timeoutMs\":20}}"));
            assertEquals(503, waiting.statusCode(), waiting.body());
            var response = Json.parseObject(waiting.body());
            assertEquals(42L, ((Number) response.get("id")).longValue());
            assertTrue(Json.str(Json.obj(response, "error"), "message", "").contains("did not run"));
            assertFalse(running.isDone());
            release.countDown();
            assertEquals("hold", Json.parseObject(running.get(3, TimeUnit.SECONDS).body()).get("result"));
            assertEquals("ping", Json.parseObject(send(post(server, "/project/rpc", PING)).body()).get("result"));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void rejectsDeclaredAndChunkedBodiesOverTheSameLimit() throws Exception {
        HttpServer server = serve(128, 1000);
        try {
            String atLimit = PING + " ".repeat(128 - PING.length());
            assertEquals(200, send(post(server, "/project/rpc", atLimit)).statusCode());
            var declared = send(post(server, "/project/rpc", atLimit + " "));
            assertEquals(413, declared.statusCode(), declared.body());
            assertTrue(declared.body().contains("128 bytes"));
            byte[] upload = (atLimit + " ").getBytes(StandardCharsets.UTF_8);
            var chunked = send(request(server, "/rpc").POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                    new ByteArrayInputStream(upload))).build());
            assertEquals(413, chunked.statusCode(), chunked.body());
            assertTrue(chunked.body().contains("128 bytes"));
            assertEquals(1, calls.get());
            assertEquals(200, send(post(server, "/project/rpc", PING)).statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowUploadsExhaustOnlyAdmissionSlotsAndExpireWithoutRestart() throws Exception {
        HttpServer server = serve(1024, 1000);
        List<Socket> uploads = new ArrayList<>();
        try {
            // Four incomplete bodies, without sending anything to the engine worker.
            for (int i = 0; i < 4; i++) {
                Socket socket = new Socket(server.getAddress().getAddress(), server.getAddress().getPort());
                uploads.add(socket);
                socket.setSoTimeout(15_000);
                socket.getOutputStream().write(("POST /project/rpc HTTP/1.1\r\nHost: localhost\r\nX-Cpiwb: 1\r\n"
                        + "Content-Length: 80\r\n\r\n{").getBytes(StandardCharsets.US_ASCII));
            }
            var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            HttpResponse<String> busy;
            do {
                busy = send(post(server, "/project/rpc", PING));
                if (busy.statusCode() == 503) break;
                Thread.sleep(20);
            } while (System.nanoTime() < until);
            assertEquals(503, busy.statusCode(), busy.body());
            assertTrue(busy.body().contains("too many HTTP requests"));
            assertEquals("1", busy.headers().firstValue("Retry-After").orElseThrow());
            // The HTTP server, not a handler timeout, closes each connection after the request read deadline.
            int pings = calls.get();
            for (Socket socket : uploads) {
                InputStream input = socket.getInputStream();
                assertEquals(-1, input.read());
            }
            // The client sees the close before the handler threads have released their admission slots
            // (slow on CI runners), so a ping right after can still be refused; it must succeed shortly after.
            HttpResponse<String> ok;
            var freed = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                ok = send(post(server, "/project/rpc", PING));
                if (ok.statusCode() != 503) break;
                Thread.sleep(20);
            } while (System.nanoTime() < freed);
            assertEquals("ping", Json.parseObject(ok.body()).get("result"), ok.body());
            assertEquals(pings + 1, calls.get(), "refused pings must not reach the engine");
        } finally {
            for (Socket socket : uploads) socket.close();
            server.stop(0);
        }
    }

    /** A Content-Length that is no number is a bad request, not an exception in the handler. */
    @Test
    void malformedContentLengthIsABadRequest() throws Exception {
        assertEquals(null, RpcServer.declaredLength(null));
        assertEquals(12L, RpcServer.declaredLength(" 12 "));
        assertEquals(-1L, RpcServer.declaredLength("12abc"));
        assertEquals(-1L, RpcServer.declaredLength("-5"));
        assertEquals(-1L, RpcServer.declaredLength("99999999999999999999"));
        HttpServer server = serve(1024, 1000);
        try (Socket socket = new Socket(server.getAddress().getAddress(), server.getAddress().getPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("POST /project/rpc HTTP/1.1\r\nHost: localhost\r\nX-Cpiwb: 1\r\n"
                    + "Content-Length: twelve\r\n\r\n" + PING).getBytes(StandardCharsets.US_ASCII));
            String reply = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // The JDK server may refuse the header itself before the handler sees it; either way it is a 400.
            assertTrue(reply.startsWith("HTTP/1.1 400"), reply);
            assertEquals(0, calls.get());
            assertEquals("ping", Json.parseObject(send(post(server, "/project/rpc", PING)).body()).get("result"));
        } finally {
            server.stop(0);
        }
    }

    /** Server settings the user passed with -D are kept; only missing ones get the engine's defaults. */
    @Test
    void userHttpServerPropertiesAreNotOverridden() {
        String key = "cpiwb.test.httpserver.property";
        try {
            RpcServer.defaultProperty(key, "engine");
            assertEquals("engine", System.getProperty(key));
            System.setProperty(key, "user");
            RpcServer.defaultProperty(key, "engine");
            assertEquals("user", System.getProperty(key));
        } finally {
            System.clearProperty(key);
        }
    }

    private static HttpRequest.Builder request(HttpServer server, String path) throws Exception {
        var addr = server.getAddress();
        var uri = new URI("http", null, addr.getHostString(), addr.getPort(), path, null, null);
        return HttpRequest.newBuilder(uri).header("X-Cpiwb", "1").header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(4));
    }

    private static HttpRequest post(HttpServer server, String path, String body) throws Exception {
        return request(server, path).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
