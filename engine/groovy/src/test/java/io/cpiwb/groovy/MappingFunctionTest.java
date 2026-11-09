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

    static Map<String, Object> call(String script, String function, List<Object> args) throws Exception {
        Json.Obj extra = Json.o().with("function", function).with("args", args);
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
        Map<String, Object> r = call(s, "getHeader", List.of("OrderId"));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("4711", mapping(r).get("returned"));
        assertEquals("single", mapping(r).get("execution"));
        assertEquals(List.of("getHeader(String, MappingContext)", "getProperty(String, MappingContext)",
                "setHeader(String, String, MappingContext)"), r.get("functions"));

        Map<String, Object> p = call(s, "getProperty", List.of("missing"));
        assertTrue(mapping(p).containsKey("returned"));
        assertNull(mapping(p).get("returned"), "an unknown property is null (SAP's Javadoc)");

        Map<String, Object> h = call(s, "setHeader", List.of("receiver", "SYS1"));
        assertEquals("SYS1", entry(h, "headers", "receiver").get("value"));
        assertEquals(List.of("receiver"), Json.obj(Json.obj(h, "changes"), "headers").get("added"));
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
        Map<String, Object> r = call(s, "join", List.of(a, b));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("context", mapping(r).get("execution"));
        assertEquals(List.of(List.of("A1", "B2"), List.of("C3")), mapping(r).get("queue"));
        assertFalse(mapping(r).containsKey("returned"));

        Map<String, Object> bad = call(s, "join", List.of(a, List.of(List.of("1"))));
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
                Json.o().with("xsiNil", true))));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("[[\"x\",{\"suppress\":true},{\"xsiNil\":true}]]", Json.write(mapping(r).get("queue")));
    }

    /** A tenant calls a String[] function for an empty context too, with an empty array (plan F61). */
    @Test
    void emptyContextIsCalledWithAnEmptyArray() throws Exception {
        String s = "def void count(String[] a, Output output, MappingContext context) {\n"
                + "  output.addValue(a.length)\n}\n";
        Map<String, Object> r = call(s, "count", List.of(List.of(List.of("a", "b"), List.of(), List.of("c"))));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals(List.of(List.of("2"), List.of("0"), List.of("1")), mapping(r).get("queue"));
    }

    /** The markers have the texts of a tenant's ResultList constants (plan F60). */
    @Test
    void markersHaveTheTenantsTexts() throws Exception {
        String s = "def void show(String[] a, Output output, MappingContext context) {\n"
                + "  output.addValue(a.join('|'))\n}\n";
        Map<String, Object> r = call(s, "show", List.of(List.of("x", Json.o().with("suppress", true),
                Json.o().with("xsiNil", true))));
        assertEquals(List.of(List.of("x|_sUpPresSeD_|_!xSi#NiL$_")), mapping(r).get("queue"));
    }

    /** The tenant's implementations have two methods beyond SAP's interfaces (plan F59). */
    @Test
    void tenantOnlyMethodsAreNotedOrUnsupported() throws Exception {
        String s = "def void f(String[] a, Output output, MappingContext context) {\n"
                + "  context.invalidateCache()\n  output.addValue('ok')\n}\n"
                + "def void g(String[] a, Output output, MappingContext context) {\n"
                + "  output.getResultList()\n}\n";
        Map<String, Object> r = call(s, "f", List.of(List.of("x")));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertTrue(Json.write(r).contains("invalidateCache() exists on a tenant"), () -> Json.write(r));
        Map<String, Object> g = call(s, "g", List.of(List.of("x")));
        assertFalse(Json.bool(g, "ok", true));
        assertTrue(Json.write(g).contains("UnsupportedInSimulator: output.getResultList()"), () -> Json.write(g));
    }

    /** As in a community guide: sum of a context, addValue with a BigDecimal, no MappingContext needed for the sum. */
    @Test
    void contextChangesAddedByTheFunctionAndClear() throws Exception {
        String s = "def void sum(String[] values, Output output, MappingContext context) {\n"
                + "  output.addValue('dropped'); output.clear()\n"
                + "  output.addValue(values.sum { it.toBigDecimal() })\n"
                + "  output.addContextChange()\n"
                + "  output.addValue(values.size())\n}\n";
        Map<String, Object> r = call(s, "sum", List.of(List.of(List.of("1.5", "2"), List.of("3"))));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals(List.of(List.of("3.5"), List.of("2"), List.of("3"), List.of("1")), mapping(r).get("queue"));
    }

    @Test
    void missingFunctionNamesTheMappingFunctions() throws Exception {
        String s = "def String upper(String a) { a.toUpperCase() }\n";
        Map<String, Object> r = call(s, "processData", List.of());
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("Message Mapping functions: upper(String)"), () -> Json.write(r));

        Map<String, Object> u = call(s, "upper", List.of());
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
        Map<String, Object> r = call(s, "setProperty", List.of("p", "v"));
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("runtime", r.get("failedPhase"));
        assertTrue(Json.write(r).contains("header_value"), () -> Json.write(r));
        assertEquals(List.of("String", "String", "MappingContext"), mapping(r).get("parameters"),
                "the function's parameters are known after a failed call");
    }

    @Test
    void processDataStaysAScriptStep() throws Exception {
        String s = "def processData(message) { message.setHeader('x', 'y'); return message }\n";
        Map<String, Object> r = call(s, "processData", List.of());
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertNull(r.get("mapping"));
        assertEquals("y", entry(r, "headers", "x").get("value"));
        assertFalse(Json.write(r).contains("Arguments are given"));

        Map<String, Object> w = call(s, "processData", List.of("unused"));
        assertTrue(Json.write(w).contains("Arguments are given"), "arguments a script step ignores are reported");
    }

    @Test
    void fieldSettersAreNotMappingFunctions() throws Exception {
        String s = "import groovy.transform.Field\n@Field String prefix = 'P-'\n"
                + "def String addPrefix(String a) { prefix + a }\n";
        Map<String, Object> r = call(s, "addPrefix", List.of("1"));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("P-1", mapping(r).get("returned"));
        assertEquals(List.of("addPrefix(String)"), r.get("functions"));
    }
}
