package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import io.cpiwb.groovy.camel.Launcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs on both engines (groovy-v1 and groovy-v2 modules share this source). */
@SuppressWarnings("unchecked")
class ScriptRunnerTest {

    static final GroovyEngine ENGINE = Launcher.engine();
    static final boolean V2 = "groovy-2.x".equals(ENGINE.describe().get("profile"));
    static final String IMPORT = V2 ? "import com.sap.it.script.v2.api.Message\n"
            : "import com.sap.gateway.ip.core.customdev.util.Message\n";

    static Map<String, Object> run(String script, Map<String, Object> input, Map<String, Object> extra) throws Exception {
        Json.Obj req = Json.o().with("script", Json.o().with("content", script).with("path", "scripts/test.groovy"))
                .with("input", input);
        if (extra != null) {
            req.putAll(extra);
        }
        return (Map<String, Object>) ENGINE.call("groovy.run", req);
    }

    static Map<String, Object> in(String body, List<Object> headers, List<Object> props) {
        return Json.o().with("body", body).with("headers", headers).with("properties", props);
    }

    static Map<String, Object> hv(String name, String value, String type) {
        return Json.o().with("name", name).with("value", value).with("type", type);
    }

    static Map<String, Object> out(Map<String, Object> r) {
        return Json.obj(r, "output");
    }

    static Map<String, Object> entry(Map<String, Object> r, String list, String name) {
        for (Object o : Json.arr(out(r), list)) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (name.equals(m.get("name"))) {
                return m;
            }
        }
        return null;
    }

    @Test
    void modifiesBodyHeadersAndProperties() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def body = message.getBody(String)\n"
                + "  message.setBody(body.toUpperCase())\n"
                + "  message.setHeader('Count', 3)\n"
                + "  message.setProperty('Seen', message.getHeaders().get('In') + '!')\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("<a>x</a>", List.of(hv("In", "hello", "String")), List.of()), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("<A>X</A>", out(r).get("body"));
        assertEquals("Integer", entry(r, "headers", "Count").get("type"));
        assertEquals("hello!", entry(r, "properties", "Seen").get("value"));
        assertEquals(List.of("Count"), Json.obj(Json.obj(r, "changes"), "headers").get("added"));
    }

    @Test
    void camelTypeConversionAndCaseInsensitiveHeaders() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def bytes = message.getBody(byte[])\n"
                + "  def reader = message.getBody(java.io.Reader)\n"
                + "  message.setProperty('len', bytes.length)\n"
                + "  message.setProperty('asInt', message.getHeader('Num', Integer))\n"
                + "  message.setProperty('ci', message.getHeaders().get('content-type'))\n"
                + "  message.setProperty('size', message.getBodySize())\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("äb", List.of(hv("Num", "42", "String"), hv("Content-Type", "text/plain", "String")),
                List.of()), Json.o().with("options", Json.o().with("bodyType", "InputStream")));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("3", entry(r, "properties", "len").get("value"));
        assertEquals("Integer", entry(r, "properties", "asInt").get("type"));
        assertEquals("42", entry(r, "properties", "asInt").get("value"));
        assertEquals("text/plain", entry(r, "properties", "ci").get("value"));
    }

    @Test
    void messageLogAndConsole() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def log = messageLogFactory.getMessageLog(message)\n"
                + "  log?.setStringProperty('Order', '42')\n"
                + "  log?.addCustomHeaderProperty('PO', '4711')\n"
                + "  log?.addAttachmentAsString('Payload', message.getBody(String), 'text/plain')\n"
                + "  println 'from println'\n"
                + "  System.out.println('from System.out')\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("<x/>", List.of(), List.of()), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        Map<String, Object> mpl = Json.obj(r, "mpl");
        assertEquals(1, Json.arr(mpl, "properties").size());
        assertEquals("4711", ((Map<String, Object>) Json.arr(mpl, "customHeaders").get(0)).get("value"));
        assertEquals("<x/>", ((Map<String, Object>) Json.arr(mpl, "attachments").get(0)).get("content"));
        assertTrue(Json.str(r, "console", "").contains("from println"));
        assertTrue(Json.str(r, "console", "").contains("from System.out"));

        Map<String, Object> off = run(s, in("<x/>", List.of(), List.of()),
                Json.o().with("platform", Json.o().with("mplEnabled", false)));
        assertTrue(Json.bool(off, "ok", false), () -> Json.write(off));
        assertEquals(0, Json.arr(Json.obj(off, "mpl"), "attachments").size());
    }

    @Test
    void secureStoreAndValueMapping() throws Exception {
        String s = IMPORT
                + "import com.sap.it.api.ITApiFactory\n"
                + "import com.sap.it.api.securestore.SecureStoreService\n"
                + "import com.sap.it.api.mapping.ValueMappingApi\n"
                + "def Message processData(Message message) {\n"
                + "  def cred = ITApiFactory.getService(SecureStoreService, null).getUserCredential('SFTP')\n"
                + "  message.setProperty('user', cred.getUsername())\n"
                + "  message.setProperty('pass', new String(cred.getPassword()))\n"
                + "  def vm = ITApiFactory.getApi(ValueMappingApi, null)\n"
                + "  message.setProperty('plant', vm.getMappedValue('S4', 'Plant', '1000', 'Shop', 'Warehouse'))\n"
                + "  message.setProperty('none', vm.getMappedValue('S4', 'Plant', '9999', 'Shop', 'Warehouse'))\n"
                + "  return message\n}";
        Map<String, Object> platform = Json.o()
                .with("credentials", Json.o().with("SFTP", Json.o().with("username", "u1").with("password", "secret")))
                .with("valueMappings", List.of(Json.o().with("sourceAgency", "S4").with("sourceIdentifier", "Plant")
                        .with("sourceValue", "1000").with("targetAgency", "Shop").with("targetIdentifier", "Warehouse")
                        .with("targetValue", "WH-A")));
        Map<String, Object> r = run(s, in("", List.of(), List.of()), Json.o().with("platform", platform));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("u1", entry(r, "properties", "user").get("value"));
        assertEquals("secret", entry(r, "properties", "pass").get("value"));
        assertEquals("WH-A", entry(r, "properties", "plant").get("value"));
        assertNull(entry(r, "properties", "none"), "a null property value is not stored by Camel");
        assertTrue(Json.arr(r, "apiCalls").size() >= 4);

        Map<String, Object> missing = run(s, in("", List.of(), List.of()), Json.o().with("platform", Json.o()));
        assertFalse(Json.bool(missing, "ok", true));
        assertTrue(Json.write(missing).contains("SecureStoreException"), () -> Json.write(missing));
    }

    @Test
    void compileErrorWithLine() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n  def x = \n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("compile", r.get("failedPhase"));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertTrue(((Number) d.get("line")).intValue() >= 3, () -> Json.write(d));
    }

    @Test
    void runtimeErrorPointsToScriptLine() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def x = null\n"
                + "  x.foo()\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("runtime", r.get("failedPhase"));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertEquals(4L, ((Number) d.get("line")).longValue(), () -> Json.write(r));
        assertTrue(Json.str(d, "message", "").contains("NullPointerException"));
    }

    @Test
    void missingFunction() throws Exception {
        String s = IMPORT + "def Message other(Message message) { message }";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("Function 'processData'"));
        Map<String, Object> custom = run(s, in("", List.of(), List.of()), Json.o().with("function", "other"));
        assertTrue(Json.bool(custom, "ok", false), () -> Json.write(custom));
    }

    @Test
    void groovyVersionDifferences() throws Exception {
        // groovy.util.XmlSlurper was removed in Groovy 4 (moved to groovy.xml): a classic 1.x -> 2.x break.
        String legacy = IMPORT + "def Message processData(Message message) {\n"
                + "  def root = new XmlSlurper().parseText(message.getBody(String))\n"
                + "  message.setProperty('n', root.i.size())\n  return message\n}";
        Map<String, Object> r = run(legacy, in("<r><i/><i/></r>", List.of(), List.of()), null);
        if (V2) {
            assertFalse(Json.bool(r, "ok", true), () -> Json.write(r));
            assertTrue(Json.write(r).contains("XmlSlurper"));
        } else {
            assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
            assertEquals("2", entry(r, "properties", "n").get("value"));
        }
        String json = IMPORT + "import groovy.json.JsonSlurper\nimport groovy.json.JsonOutput\n"
                + "def Message processData(Message message) {\n"
                + "  def o = new JsonSlurper().parseText(message.getBody(String))\n"
                + "  message.setBody(JsonOutput.toJson([sum: o.items.sum()]))\n  return message\n}";
        Map<String, Object> j = run(json, in("{\"items\":[1,2,3]}", List.of(), List.of()), null);
        assertEquals("{\"sum\":6}", out(j).get("body"), () -> Json.write(j));
        assertEquals(V2 ? "4.0.29" : "2.4.21", ENGINE.describe().get("groovy"));
    }

    @Test
    void wildcardImportIsLinted() throws Exception {
        String s = "import com.sap.gateway.ip.core.customdev.util.Message\nimport groovy.xml.*\n"
                + "def processData(message) { message }";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertTrue(Json.write(r).contains("Wildcard import"), () -> Json.write(r));
    }
}
