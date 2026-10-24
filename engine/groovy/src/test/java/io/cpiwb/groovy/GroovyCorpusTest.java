package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the community script corpus (src/test/resources/corpus/pizug, MIT) on the engine and compares
 * with the expected files shipped with each example.
 */
@SuppressWarnings("unchecked")
class GroovyCorpusTest {

    /** Examples that cannot run on Groovy 4 without changes (legacy groovy.util XML classes). */
    private static final List<String> FAILS_ON_V2 = List.of("edi-segment-counter");

    @TestFactory
    Stream<DynamicTest> corpus() throws Exception {
        Path root = Path.of(getClass().getResource("/corpus/pizug").toURI());
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        return dirs.stream().map(d -> DynamicTest.dynamicTest(d.getFileName().toString(), () -> runExample(d)));
    }

    private void runExample(Path dir) throws Exception {
        String name = dir.getFileName().toString();
        Path script;
        try (Stream<Path> s = Files.list(dir)) {
            script = s.filter(p -> p.toString().endsWith(".groovy")).findFirst().orElseThrow();
        }
        Json.Obj input = Json.o()
                .with("body", read(dir, "input.body.xml", read(dir, "input.body.csv", "")))
                .with("headers", props(dir.resolve("input.header")))
                .with("properties", props(dir.resolve("input.properties")));
        Json.Obj platform = Json.o().with("credentials",
                Json.o().with("Partner1_SFTP", Json.o().with("username", "My Username").with("password", "My Password"))
                        // the example's property file carries a trailing blank in the alias
                        .with("Partner1_SFTP ", Json.o().with("username", "My Username").with("password", "My Password")));
        Map<String, Object> r = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("groovy.run", Json.o()
                .with("script", Json.o().with("content", Files.readString(script, StandardCharsets.UTF_8))
                        .with("path", "scripts/" + script.getFileName()))
                .with("input", input).with("platform", platform));

        if (ScriptRunnerTest.V2 && FAILS_ON_V2.contains(name)) {
            assertFalse(Json.bool(r, "ok", true), "expected Groovy 4 incompatibility for " + name);
            return;
        }
        assertTrue(Json.bool(r, "ok", false), () -> name + ": " + Json.write(r));
        Map<String, Object> out = Json.obj(r, "output");
        // XML is compared ignoring indentation: pretty-printing (XmlUtil.serialize) depends on the JDK
        // version, and the expected files were produced on an unknown one.
        if (Files.exists(dir.resolve("expected.body.xml"))) {
            assertEquals(squeeze(read(dir, "expected.body.xml", "")), squeeze(Json.str(out, "body", "")), name + " body");
        }
        if (Files.exists(dir.resolve("expected.body.csv"))) {
            assertEquals(normalize(read(dir, "expected.body.csv", "")), normalize(Json.str(out, "body", "")), name + " body");
        }
        for (String f : List.of("expected.header.txt", "expected.properties.txt", "expected.properties")) {
            if (!Files.exists(dir.resolve(f))) {
                continue;
            }
            String list = f.contains("header") ? "headers" : "properties";
            Properties exp = new Properties();
            exp.load(new StringReader(Files.readString(dir.resolve(f), StandardCharsets.UTF_8)));
            for (String key : exp.stringPropertyNames()) {
                String actual = null;
                for (Object o : Json.arr(out, list)) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    if (key.equals(m.get("name"))) {
                        actual = (String) m.get("value");
                    }
                }
                assertEquals(exp.getProperty(key).trim(), actual == null ? null : actual.trim(), name + " " + list + " " + key);
            }
        }
    }

    /** Drops the XML declaration and whitespace around tags. */
    private static String squeeze(String s) {
        return s.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "").replaceAll(">\\s+", ">").replaceAll("\\s+<", "<").strip();
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").replaceAll("[ \t]+\n", "\n").strip();
    }

    private static String read(Path dir, String file, String def) throws Exception {
        Path p = dir.resolve(file);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : def;
    }

    private static List<Object> props(Path file) throws Exception {
        List<Object> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        Properties p = new Properties();
        p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        for (String k : p.stringPropertyNames()) {
            out.add(Json.o().with("name", k).with("value", p.getProperty(k)).with("type", "String"));
        }
        return out;
    }

}
