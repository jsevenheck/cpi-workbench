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
}
