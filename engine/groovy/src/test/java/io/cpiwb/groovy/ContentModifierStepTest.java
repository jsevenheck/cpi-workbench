package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rows use the iFlow encoding (types and Delete names as in exported .iflw files). Runs on both engines. */
@SuppressWarnings("unchecked")
class ContentModifierStepTest {

    static Map<String, Object> row(String action, String name, String type, String value, String datatype) {
        return Json.o().with("action", action).with("name", name).with("type", type).with("value", value).with("datatype", datatype);
    }

    static Map<String, Object> run(List<Object> headers, List<Object> properties, String bodyType, String body) throws Exception {
        Map<String, Object> input = ScriptRunnerTest.in("<Product><ID>HT-1051</ID></Product>",
                List.of(ScriptRunnerTest.hv("SenderId", "ERP", "String"), ScriptRunnerTest.hv("X-Trace", "1", "String"),
                        ScriptRunnerTest.hv("x-other", "2", "String")),
                List.of(ScriptRunnerTest.hv("Partner", "ACME", "String")));
        Map<String, Object> r = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run", Json.o()
                .with("headers", headers).with("properties", properties).with("bodyType", bodyType).with("body", body)
                .with("input", input));
        return r;
    }

    static Map<String, String> values(Map<String, Object> r, String list) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Object o : Json.arr(Json.obj(r, "output"), list)) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.put((String) m.get("name"), m.get("value") + ":" + m.get("type"));
        }
        return out;
    }

    @Test
    void rowsRunTopToBottomThenTheBody() throws Exception {
        Map<String, Object> r = run(
                List.of(row("Create", "Order", "constant", "4711", ""),
                        row("Create", "Key", "expression", "${header.SenderId}-${header.Order}", "java.lang.String"),
                        row("Create", "Copy", "property", "Partner", "")),
                List.of(row("Create", "timestamp", "expression", "${date:now:yyyy}", ""),
                        row("Create", "Count", "expression", "${header.Order}", "Integer"),
                        row("Create", "FromHeader", "header", "Key", "")),
                "expression", "${property.Partner}:${in.body}");
        Map<String, String> h = values(r, "headers");
        assertEquals("ERP-4711:String", h.get("Key"));
        assertEquals("ACME:String", h.get("Copy"));
        Map<String, String> p = values(r, "properties");
        assertEquals("4711:Integer", p.get("Count"));
        assertEquals("ERP-4711:String", p.get("FromHeader"));
        assertEquals("ACME:<Product><ID>HT-1051</ID></Product>", Json.obj(r, "output").get("body"));
        assertTrue(((List<Object>) Json.obj(Json.obj(r, "changes"), "headers").get("added")).contains("Order"));
    }

    @Test
    void xpathRowsArriveEvaluated() throws Exception {
        Map<String, Object> xp = row("Create", "Id", "xpath", "//ID/text()", "java.lang.Integer");
        Map<String, Object> r = run(List.of(), List.of(xp), "constant", "");
        assertFalse(Json.bool(r, "ok", true));
        xp.put("resolved", "1051");
        r = run(List.of(), List.of(xp), "constant", "");
        assertEquals("1051:Integer", values(r, "properties").get("Id"));
        // An empty body leaves the message body as it is.
        assertEquals("<Product><ID>HT-1051</ID></Product>", Json.obj(r, "output").get("body"));
    }

    @Test
    void byteBodiesArePreservedUnlessReplaced() throws Exception {
        for (String base64 : List.of("AP8H", "YWJj", "")) {
            Json.Obj req = Json.o().with("input", Json.o().with("bodyBase64", base64))
                    .with("headers", List.of(row("Create", "Copy", "expression", "${body}", "")))
                    .with("properties", List.of(row("Create", "Format", "constant", "binary", "")));
            Map<String, Object> result = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run", req);
            assertEquals(base64 + ":byte[]", values(result, "headers").get("Copy"));
            assertEquals("binary:String", values(result, "properties").get("Format"));
            Map<String, Object> output = Json.obj(result, "output");
            assertEquals(true, output.get("binary"));
            assertEquals(base64, output.get("bodyBase64"));
            assertEquals(java.util.Base64.getDecoder().decode(base64).length, ((Number) output.get("bodySize")).intValue());
            assertFalse(output.containsKey("body"));
            assertTrue(Json.arr(result, "diagnostics").isEmpty());

            result = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run",
                    req.with("body", "format=${property.Format}").with("bodyType", "expression"));
            assertTrue(Json.bool(result, "ok", false), result.toString());
            output = Json.obj(result, "output");
            assertEquals("format=binary", output.get("body"));
            assertFalse(output.containsKey("bodyBase64"));
        }
    }

    @Test
    void bytesThatAreNoTextWarnWhenReadAsText() throws Exception {
        // "AP8H" is 00 ff 07: 0xff is no UTF-8. "w6Rh" is "äa" in UTF-8 and stays text without a warning.
        for (String base64 : List.of("AP8H", "w6Rh")) {
            boolean text = "w6Rh".equals(base64);
            Json.Obj req = Json.o().with("input", Json.o().with("bodyBase64", base64))
                    .with("headers", List.of(row("Create", "Text", "expression", "${body}", "java.lang.String")));
            Map<String, Object> result = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run", req);
            assertTrue(Json.bool(result, "ok", false), result.toString());
            String diagnostics = Json.arr(result, "diagnostics").toString();
            assertEquals(!text, diagnostics.contains("Header Text: the binary body is not valid UTF-8"), diagnostics);
            if (text) {
                assertEquals("äa:String", values(result, "headers").get("Text"));
            }

            result = (Map<String, Object>) ScriptRunnerTest.ENGINE.call("contentModifier.run", Json.o()
                    .with("input", Json.o().with("bodyBase64", base64)).with("bodyType", "expression").with("body", "[${body}]"));
            assertTrue(Json.bool(result, "ok", false), result.toString());
            diagnostics = Json.arr(result, "diagnostics").toString();
            assertEquals(!text, diagnostics.contains("Message Body: the binary body is not valid UTF-8"), diagnostics);
            if (text) {
                assertEquals("[äa]", Json.obj(result, "output").get("body"));
            }
        }
    }

    @Test
    void deleteByNameAndPattern() throws Exception {
        Map<String, Object> r = run(List.of(row("Delete", "$include=X-*", "constant", "", ""),
                        row("Delete", "$name=SenderId", "constant", "", "")),
                List.of(row("Delete", "$include=*,$exclude=Part*", "constant", "", "")), "constant", "");
        Map<String, String> h = values(r, "headers");
        assertFalse(h.containsKey("X-Trace"));
        assertFalse(h.containsKey("SenderId"));
        assertTrue(values(r, "properties").containsKey("Partner"));
        // A Delete row without a name is an error, not a silent no-op.
        assertFalse(Json.bool(run(List.of(row("Delete", " ", "constant", "", "")), List.of(), "constant", ""), "ok", true));
    }

    @Test
    void pausedRunsAreProjectBoundSingleUseAndBounded() {
        ContentModifierStep step = new ContentModifierStep();
        Path project = Path.of("project-a");
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> partial = step.advance(Json.o().with("headers", List.of(row("Create", "ID", "constant", "" + i, ""))), project);
            tokens.add(Json.o().with("continuation", partial.get("continuation")));
        }
        assertThrows(IllegalStateException.class, () -> step.advance(Json.o(), project));
        Map<String, Object> first = tokens.get(0);
        assertThrows(IllegalArgumentException.class, () -> step.run(first, Path.of("project-b")));
        assertEquals(false, step.discard(first, Path.of("project-b")).get("discarded"));
        assertEquals("0:String", values(step.run(first, project), "headers").get("ID"));
        assertThrows(IllegalArgumentException.class, () -> step.run(first, project));
        for (Map<String, Object> token : tokens.subList(1, tokens.size())) {
            assertEquals(true, step.discard(token, project).get("discarded"));
        }
    }

    @Test
    void variablesAndUnsupportedTypes() throws Exception {
        Map<String, Object> var = row("Create", "Last", "persisted variables", "lastRun", "");
        var.put("default", "never");
        Map<String, Object> r = run(List.of(var), List.of(), "constant", "");
        assertEquals("never:String", values(r, "headers").get("Last"));
        r = run(List.of(row("Create", "No", "numberRange", "ORDERS", "")), List.of(), "constant", "");
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.arr(r, "diagnostics").toString().contains("UnsupportedInSimulator"));
        r = run(List.of(), List.of(row("Create", "Nothing", "expression", "${null}", "")), "constant", "");
        assertNull(((Map<String, Object>) Json.arr(r, "trace").get(0)).get("value"));
    }
}
