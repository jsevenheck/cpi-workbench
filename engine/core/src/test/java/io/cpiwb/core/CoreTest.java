package io.cpiwb.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
