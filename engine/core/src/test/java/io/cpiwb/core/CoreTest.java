package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {

    @Test
    void jsonRoundTrip() {
        String text = "{\"a\":1,\"b\":[true,false,null],\"c\":\"x\\n\\\"y\\\" \\u00e4\",\"d\":1.5,\"e\":{}}";
        Object parsed = Json.parse(text);
        assertEquals(text.replace("\\u00e4", "ä"), Json.write(parsed));
        Map<String, Object> m = Json.parseObject(text);
        assertEquals(1L, m.get("a"));
        assertEquals(new BigDecimal("1.5"), m.get("d"));
    }

    @Test
    void jsonRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]x"));
    }

    @Test
    void typedValues() {
        assertEquals(42, TypedValue.toJava("Integer", "42"));
        assertEquals(42L, TypedValue.toJava("Long", " 42 "));
        assertEquals(Boolean.TRUE, TypedValue.toJava("Boolean", "true"));
        assertNull(TypedValue.toJava("null", "whatever"));
        assertEquals("Integer", TypedValue.typeOf(7));
        assertEquals("byte[]", TypedValue.typeOf(new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> TypedValue.toJava("Integer", "abc"));
        assertThrows(IllegalArgumentException.class, () -> TypedValue.toJava("Foo", "1"));
    }

    @Test
    void readMapAcceptsListAndShorthand() {
        Map<String, Object> list = TypedValue.readMap(List.of(
                Map.of("name", "a", "value", "1", "type", "Integer"),
                Map.of("name", "b", "value", "x", "disabled", true)));
        assertEquals(Map.of("a", 1), list);
        Map<String, Object> shorthand = TypedValue.readMap(Map.of("x", "1", "y", Map.of("value", "2", "type", "Long")));
        assertEquals("1", shorthand.get("x"));
        assertEquals(2L, shorthand.get("y"));
    }

    @Test
    void diffDetectsTypeChanges() {
        Map<String, Object> d = SimExchange.diff(Map.of("a", "1", "b", "x"), Map.of("a", 1, "c", "y"));
        assertEquals(List.of("c"), d.get("added"));
        assertEquals(List.of("a"), d.get("changed"));
        assertEquals(List.of("b"), d.get("removed"));
    }

    @Test
    void xmlCompareIgnoresFormatting() {
        assertNull(TestRunner.compare("<a>\n  <b x=\"1\">t</b>\n</a>", "<a><b x=\"1\">t</b></a>", "xml"));
        String diff = TestRunner.compare("<a><b>t</b></a>", "<a><b>u</b></a>", "xml");
        assertTrue(diff.contains("expected text 't'"), diff);
        assertTrue(TestRunner.compare("x", "y", "text").startsWith("line 1"));
    }

    @Test
    void corsAcceptsOnlyExactLocalOrigins() {
        for (String ok : List.of("http://localhost:5173", "http://127.0.0.1:7777", "tauri://localhost", "http://tauri.localhost",
                "https://tauri.localhost")) {
            assertTrue(RpcServer.isLocalOrigin(ok), ok);
        }
        for (String bad : List.of("http://tauri.localhost.example.invalid", "http://localhost.example.invalid:80",
                "http://127.0.0.1.example.invalid:80", "tauri://localhost.evil", "http://localhost", "https://example.com",
                "http://user@localhost:5173", "null", "file://localhost")) {
            assertFalse(RpcServer.isLocalOrigin(bad), bad);
        }
    }

    @Test
    void closedProjectRefusesFileAccess(@TempDir Path tmp) throws Exception {
        ProjectFiles files = new ProjectFiles();
        files.open(Map.of("dir", tmp.toString()));
        Files.writeString(tmp.resolve("a.txt"), "x");
        assertEquals("x", files.read(Map.of("path", "a.txt")).get("content"));
        files.close();
        assertNull(files.root());
        assertThrows(IllegalStateException.class, () -> files.read(Map.of("path", "a.txt")));
    }

    /** Paths are checked where they really lead: a symbolic link inside the project may not point outside. */
    @Test
    void symbolicLinksStayInsideTheProject(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(tmp.resolve("outside.txt"), "secret");
        Files.createDirectories(tmp.resolve("outdir"));
        Files.writeString(project.resolve("inside.txt"), "inside");
        try {
            Files.createSymbolicLink(project.resolve("out.txt"), tmp.resolve("outside.txt"));
            Files.createSymbolicLink(project.resolve("outdir"), tmp.resolve("outdir"));
            Files.createSymbolicLink(project.resolve("in.txt"), project.resolve("inside.txt"));
            Files.createSymbolicLink(project.resolve("dangling.txt"), tmp.resolve("not-yet.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here: " + e);
        }
        ProjectFiles files = new ProjectFiles();
        files.open(Map.of("dir", project.toString()));
        assertThrows(IllegalArgumentException.class, () -> files.read(Map.of("path", "out.txt")));
        assertThrows(IllegalArgumentException.class, () -> files.write(Map.of("path", "outdir/new.txt", "content", "x")));
        assertThrows(IllegalArgumentException.class, () -> files.write(Map.of("path", "dangling.txt", "content", "x")));
        assertFalse(Files.exists(tmp.resolve("outdir/new.txt")));
        assertFalse(Files.exists(tmp.resolve("not-yet.txt")), "a dangling link must not create its target");
        // Links inside the project and new files still work.
        assertEquals("inside", files.read(Map.of("path", "in.txt")).get("content"));
        files.write(Map.of("path", "sub/new.txt", "content", "y"));
        assertEquals("y", files.read(Map.of("path", "sub/new.txt")).get("content"));
    }

    /** A page reached by DNS rebinding is same-origin for the browser; its Host header still names the foreign host. */
    @Test
    void httpAnswersOnlyThisMachinesNames() {
        for (String ok : List.of("localhost", "localhost:7777", "127.0.0.1:7777", "LOCALHOST:1", "[::1]:7777", "[::1]")) {
            assertTrue(RpcServer.isLocalHost(ok), ok);
        }
        for (String bad : new String[] {null, "", "attacker.example:7777", "localhost.attacker.example", "127.0.0.1.attacker.example:80",
                "localhost:7777@attacker.example", "localhost:x", "[::1].attacker.example", "10.0.0.5:7777"}) {
            assertFalse(RpcServer.isLocalHost(bad), String.valueOf(bad));
        }
    }

    @Test
    void httpRefusesForeignHostAndOrigin() throws Exception {
        RpcHandler echo = new RpcHandler() {
            public Object call(String method, Map<String, Object> params) {
                return Json.o().with("pong", true);
            }

            public Map<String, Object> describe() {
                return Json.o();
            }
        };
        com.sun.net.httpserver.HttpServer server = new RpcServer(echo, 5000).serveHttp(0, null);
        try {
            int port = server.getAddress().getPort();
            assertEquals(200, post(port, "127.0.0.1:" + port, null));
            assertEquals(200, post(port, "localhost:" + port, "http://localhost:" + port));
            assertEquals(403, post(port, "attacker.example:" + port, null));
            assertEquals(403, post(port, "127.0.0.1:" + port, "http://attacker.example:" + port));
        } finally {
            server.stop(0);
        }
    }

    /** A raw request: java.net.http does not let a caller set Host. */
    private static int post(int port, String host, String origin) throws Exception {
        String body = "{\"id\":1,\"method\":\"ping\"}";
        try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)) {
            s.getOutputStream().write(("POST /rpc HTTP/1.1\r\nHost: " + host + "\r\n" + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "X-Cpiwb: 1\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    @Test
    void textTakesTheEncodingItsXmlDeclarationNames(@TempDir Path tmp) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>Müller</a>";
        assertEquals(java.nio.charset.StandardCharsets.ISO_8859_1, TextFiles.declaredCharset(xml));
        assertNull(TextFiles.declaredCharset("<a>encoding=\"ISO-8859-1\"</a>"));
        assertNull(TextFiles.declaredCharset("<?xml version='1.0' encoding='no-such-charset'?><a/>"));
        SimExchange in = SimExchange.fromJson(Json.o().with("body", xml));
        assertEquals("ISO-8859-1", in.getCharset().name());
        assertEquals(xml, new String(in.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1));
        // A character the encoding lacks is an error, not a silent "?".
        IllegalArgumentException lacks = assertThrows(IllegalArgumentException.class,
                () -> SimExchange.fromJson(Json.o().with("body", xml.replace("Müller", "5 €"))));
        assertTrue(lacks.getMessage().contains("'€' in line 1"), lacks.getMessage());
        // An explicit encoding wins, and plain text stays UTF-8.
        assertEquals("UTF-8", SimExchange.fromJson(Json.o().with("body", xml).with("encoding", "UTF-8")).getCharset().name());
        assertEquals("UTF-8", SimExchange.fromJson(Json.o().with("body", "Müller")).getCharset().name());

        // Files: read and written in the declared encoding, so the file stays what its declaration says.
        Files.write(tmp.resolve("latin1.xml"), xml.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Files.write(tmp.resolve("binary.bin"), new byte[] {(byte) 0xC3, (byte) 0x28, 0, 1});
        ProjectFiles files = new ProjectFiles();
        files.open(Json.o().with("dir", tmp.toString()));
        assertEquals(xml, files.read(Json.o().with("path", "latin1.xml")).get("content"));
        files.write(Json.o().with("path", "copy.xml").with("content", xml));
        assertEquals(-1, java.util.Arrays.mismatch(Files.readAllBytes(tmp.resolve("latin1.xml")), Files.readAllBytes(tmp.resolve("copy.xml"))));
        // Saving a character the declared encoding lacks is an error too, not a "?" in the file.
        IllegalArgumentException lost = assertThrows(IllegalArgumentException.class,
                () -> files.write(Json.o().with("path", "euro.xml").with("content", xml.replace("Müller", "5 €"))));
        assertTrue(lost.getMessage().contains("'€' in line 1"), lost.getMessage());
        assertFalse(Files.exists(tmp.resolve("euro.xml")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> files.read(Json.o().with("path", "binary.bin")));
        assertTrue(e.getMessage().contains("not UTF-8"), e.getMessage());
    }

    @Test
    void initWritesValidJsonForAnyFolderName(@TempDir Path tmp) throws Exception {
        Path dir;
        try {
            dir = Files.createDirectories(tmp.resolve("we\"ird"));
        } catch (java.nio.file.InvalidPathException e) {
            // Windows allows no quote in a file name, so the case cannot arise there.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no quotes in file names here");
            return;
        }
        new ProjectFiles().open(Json.o().with("dir", dir.toString()).with("init", true));
        assertEquals("we\"ird", Json.parseObject(Files.readString(dir.resolve("cpi-workbench.json"))).get("name"));
    }

    @Test
    void aCaseWithAnUnknownProfileIsAnErrorNotSkipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("case.json"), "{\"step\":\"xslt\",\"profile\":\"saxon-he-9\"}");
        TestRunner.CaseResult r = new TestRunner(null, "saxon-he-9.9").run(tmp.resolve("case.json"));
        assertEquals("error", r.status);
        assertTrue(r.messages.get(0).contains("Unknown profile 'saxon-he-9'"), r.messages.toString());
        Files.writeString(tmp.resolve("case.json"), "{\"step\":\"xslt\",\"profile\":\"groovy-1.x\"}");
        assertEquals("skipped", new TestRunner(null, "saxon-he-9.9").run(tmp.resolve("case.json")).status);
    }

    @Test
    void testRunExitCodes() {
        Map<String, Object> none = Json.o().with("results", List.of()).with("passed", 0).with("failed", 0).with("skipped", 0);
        assertEquals(1, Cli.printTestResults(none, true, false));
        Map<String, Object> skipped = Json.o().with("results", List.of(Json.o().with("status", "skipped").with("name", "x").with("ms", 0).with("messages", List.of())))
                .with("passed", 0).with("failed", 0).with("skipped", 1);
        assertEquals(0, Cli.printTestResults(skipped, true, false));
        assertEquals(1, Cli.printTestResults(skipped, true, true));
    }

    /** Browser mode: the supervising process starts the same engine again, marked as the one that serves. */
    @Test
    void relaunchCommandIsThisCommandLineAsWorker() {
        List<String> c = RpcServer.relaunchCommand(new String[] {"serve", "--http", "7777"});
        assertTrue(c != null && c.size() > 5, String.valueOf(c));
        assertEquals(List.of("serve", "--http", "7777", RpcServer.WORKER), c.subList(c.size() - 4, c.size()));
        assertTrue(Files.isRegularFile(Path.of(c.get(0))), c.get(0));
        assertEquals("7777", new Cli.Args(c.subList(c.size() - 4, c.size()).toArray(new String[0])).get("--http"));
    }
}
