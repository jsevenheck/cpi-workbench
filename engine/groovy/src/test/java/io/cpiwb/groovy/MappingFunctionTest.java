package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.cpiwb.groovy.ScriptRunnerTest.entry;
import static io.cpiwb.groovy.ScriptRunnerTest.hv;
import static io.cpiwb.groovy.ScriptRunnerTest.in;
import static io.cpiwb.groovy.ScriptRunnerTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom functions of Message Mapping scripts, on both engines. */
@SuppressWarnings("unchecked")
class MappingFunctionTest {

    static final String IMPORTS = "import com.sap.it.api.mapping.MappingContext\nimport com.sap.it.api.mapping.Output\n";

    static Map<String, Object> call(String script, String function, List<Object> args, String execution) throws Exception {
        Json.Obj extra = Json.o().with("function", function).with("args", args);
        if (execution != null) {
            extra.with("options", Json.o().with("execution", execution));
        }
        return run(IMPORTS + script, in("", List.<Object>of(hv("OrderId", "4711", "String")),
                List.<Object>of(hv("quantity", "3", "String"))), extra);
    }

    static Map<String, Object> mapping(Map<String, Object> r) {
        return Json.obj(r, "mapping");
    }

    /** SAP's sample "Access Header and Properties in Message Mapping". */
    @Test
    void singleValuesReadAndSetHeadersAndProperties() throws Exception {
        String s = "def String getHeader(String headerName, MappingContext context) {\n"
                + "  return context.getHeader(headerName)\n}\n"
                + "def String getProperty(String propertyName, MappingContext context) {\n"
                + "  return context.getProperty(propertyName)\n}\n"
                + "def String setHeader(String name, String value, MappingContext context) {\n"
                + "  context.setHeader(name, value)\n  return value\n}\n";
        Map<String, Object> r = call(s, "getHeader", List.of("OrderId"), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("4711", mapping(r).get("returned"));
        assertEquals("single", mapping(r).get("execution"));
        assertEquals(List.of("getHeader(String, MappingContext)", "getProperty(String, MappingContext)",
                "setHeader(String, String, MappingContext)"), r.get("functions"));

        Map<String, Object> p = call(s, "getProperty", List.of("missing"), null);
        assertTrue(mapping(p).containsKey("returned"));
        assertNull(mapping(p).get("returned"), "an unknown property is null (SAP's Javadoc)");

        Map<String, Object> h = call(s, "setHeader", List.of("receiver", "SYS1"), null);
        assertEquals("SYS1", entry(h, "headers", "receiver").get("value"));
        assertEquals(List.of("receiver"), Json.obj(Json.obj(h, "changes"), "headers").get("added"));
    }

    @Test
    void queueGetsAllContextsWithContextChangesBetween() throws Exception {
        String s = "def void count(String[] products, Output output, MappingContext context) {\n"
                + "  int n = 0\n"
                + "  for (p in products) {\n"
                + "    if (output.isContextChange(p)) { output.addValue(n); output.addContextChange(); n = 0 }\n"
                + "    else n++\n"
                + "  }\n"
                + "  output.addValue(n)\n}\n";
        Map<String, Object> r = call(s, "count", List.of(List.of(List.of("A", "B"), List.of("C"), List.of())), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("queue", mapping(r).get("execution"));
        assertEquals(List.of(List.of("2"), List.of("1"), List.of("0")), mapping(r).get("queue"));
        assertFalse(mapping(r).containsKey("returned"));
    }

    @Test
    void contextCallsOncePerContextWithoutContextChanges() throws Exception {
        String s = "def void join(String[] a, String[] b, Output output, MappingContext context) {\n"
                + "  for (int i = 0; i < a.length; i++) {\n"
                + "    if (output.isContextChange(a[i])) throw new IllegalStateException('context change')\n"
                + "    output.addValue(a[i] + b[i])\n"
                + "  }\n}\n";
        List<Object> a = List.of(List.of("A", "B"), List.of("C"));
        List<Object> b = List.of(List.of("1", "2"), List.of("3"));
        Map<String, Object> r = call(s, "join", List.of(a, b), "context");
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals(List.of(List.of("A1", "B2"), List.of("C3")), mapping(r).get("queue"));

        Map<String, Object> bad = call(s, "join", List.of(a, List.of(List.of("1"))), "context");
        assertFalse(Json.bool(bad, "ok", true));
        assertTrue(Json.write(bad).contains("same number of contexts"), () -> Json.write(bad));
    }

    @Test
    void suppressAndXsiNilPassThroughAndShowInTheQueue() throws Exception {
        String s = "def void pass(String[] a, Output output, MappingContext context) {\n"
                + "  for (v in a) {\n"
                + "    if (output.isSuppress(v)) output.addSuppress()\n"
                + "    else if (output.isXSINIL(v)) output.addXSINIL()\n"
                + "    else output.addValue(v)\n"
                + "  }\n}\n";
        Map<String, Object> r = call(s, "pass", List.of(List.of("x", Json.o().with("suppress", true),
                Json.o().with("xsiNil", true))), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("[[\"x\",{\"suppress\":true},{\"xsiNil\":true}]]", Json.write(mapping(r).get("queue")));
    }

    @Test
    void clearAndAddValueOfAMarker() throws Exception {
        String s = "def void f(String[] a, Output output, MappingContext context) {\n"
                + "  output.addValue('dropped'); output.clear()\n"
                + "  for (v in a) output.addValue(v)\n}\n";
        Map<String, Object> r = call(s, "f", List.of(List.of(List.of("A"), List.of("B"))), null);
        assertEquals(List.of(List.of("A"), List.of("B")), mapping(r).get("queue"),
                "a context change passed to addValue stays a context change");
    }

    @Test
    void missingFunctionNamesTheMappingFunctions() throws Exception {
        String s = "def String upper(String a) { a.toUpperCase() }\n";
        Map<String, Object> r = call(s, "processData", List.of(), null);
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("Message Mapping functions: upper(String)"), () -> Json.write(r));

        Map<String, Object> u = call(s, "upper", List.of(), null);
        assertTrue(Json.bool(u, "ok", false), () -> Json.write(u));
        assertEquals("", mapping(u).get("returned"));
        assertTrue(Json.write(u).contains("has no value"), "a missing argument is reported");
    }

    @Test
    void scriptExceptionsAreRuntimeErrorsWithTheLine() throws Exception {
        // SAP's setProperty sample returns header_value, which that function does not define.
        String s = "def String setProperty(String property_name, String property_value, MappingContext context) {\n"
                + "  context.setProperty(property_name, property_value)\n"
                + "  return header_value\n}\n";
        Map<String, Object> r = call(s, "setProperty", List.of("p", "v"), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("runtime", r.get("failedPhase"));
        assertTrue(Json.write(r).contains("header_value"), () -> Json.write(r));
        assertEquals(List.of("String", "String", "MappingContext"), mapping(r).get("parameters"),
                "the function's parameters are known after a failed call");
    }

    @Test
    void processDataStaysAScriptStep() throws Exception {
        String s = "def processData(message) { message.setHeader('x', 'y'); return message }\n";
        Map<String, Object> r = call(s, "processData", List.of(), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertNull(r.get("mapping"));
        assertEquals("y", entry(r, "headers", "x").get("value"));
    }
}
