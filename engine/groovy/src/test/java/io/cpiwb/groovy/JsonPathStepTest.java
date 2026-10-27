package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class JsonPathStepTest {

    static final String ORDER = "{\"id\": 4711, \"items\": [{\"sku\": \"M-100\", \"qty\": 2, \"price\": 15.50},"
            + " {\"sku\": \"M-200\", \"qty\": 1, \"price\": 7}]}";

    static Map<String, Object> eval(String expr, String body) throws Exception {
        return (Map<String, Object>) ScriptRunnerTest.ENGINE.call("jsonpath.eval",
                Json.o().with("expression", expr).with("input", Json.o().with("body", body)));
    }

    static List<Map<String, Object>> items(Map<String, Object> r) {
        return (List<Map<String, Object>>) (List<?>) Json.arr(r, "items");
    }

    @Test
    void wildcardReturnsEveryMatchWithItsPath() throws Exception {
        Map<String, Object> r = eval("$.items[*].sku", ORDER);
        assertTrue(Json.bool(r, "ok", false));
        assertEquals(false, r.get("definite"));
        assertEquals(2, items(r).size());
        assertEquals("$['items'][0]['sku']", items(r).get(0).get("path"));
        assertEquals("\"M-100\"", items(r).get(0).get("value"));
        assertEquals("String", items(r).get(0).get("type"));
    }

    @Test
    void filterAndObjectValues() throws Exception {
        Map<String, Object> r = eval("$.items[?(@.qty > 1)]", ORDER);
        assertEquals(1, items(r).size());
        assertTrue(items(r).get(0).get("value").toString().contains("\"sku\":\"M-100\""));
        assertEquals("Map", items(r).get(0).get("type"));
        assertEquals("Integer", items(eval("$.id", ORDER)).get(0).get("type"));
        assertEquals("Double", items(eval("$.items[0].price", ORDER)).get(0).get("type"));
    }

    @Test
    void functionHasValueButNoPath() throws Exception {
        Map<String, Object> r = eval("$.items.length()", ORDER);
        assertEquals("2", items(r).get(0).get("value"));
        assertNull(items(r).get(0).get("path"));
    }

    @Test
    void missingDefinitePathIsNotAnError() throws Exception {
        Map<String, Object> r = eval("$.customer.name", ORDER);
        assertTrue(Json.bool(r, "ok", false));
        assertEquals(0, items(r).size());
        assertTrue(Json.arr(r, "diagnostics").toString().contains("PathNotFoundException"));
        assertEquals(0, items(eval("$.items[*].customer", ORDER)).size());
    }

    @Test
    void invalidExpressionOrJsonIsReported() throws Exception {
        assertFalse(Json.bool(eval("$.items[?(@.qty >", ORDER), "ok", true));
        assertFalse(Json.bool(eval("$.id", "{\"id\": "), "ok", true));
    }
}
