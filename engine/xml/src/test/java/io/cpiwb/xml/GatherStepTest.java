package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Combine reproduces SAP's result example ("Define Gather and Join"). */
@SuppressWarnings("unchecked")
class GatherStepTest {

    static final String[] NOTES = {"Jack", "Chris", "Tove"};

    static String note(String to) {
        return "<note>\n<to>" + to + "</to>\n<from>Jill</from>\n</note>";
    }

    static Map<String, Object> gather(String format, String algorithm, String source, String target, List<String> messages) {
        return new GatherStep().run(Json.o().with("messageType", format).with("algorithm", algorithm)
                .with("sourceXPath", source).with("targetXPath", target).with("messages", messages));
    }

    static String body(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        return (String) r.get("body");
    }

    @Test
    void combine() {
        List<String> notes = List.of(note("Jack"), note("Chris"), "<?xml version=\"1.0\"?>\n" + note("Tove"));
        String head = "<?xml version='1.0' encoding='UTF-8'?><multimap:Messages xmlns:multimap=\"http://sap.com/xi/XI/SplitAndMerge\">";
        assertEquals(head + "<multimap:Message1>" + note("Jack") + note("Chris") + note("Tove") + "</multimap:Message1></multimap:Messages>",
                body(gather("sameXml", "combine", "", "", notes)));
        assertEquals(head + "<multimap:Message1>" + note("Jack") + "</multimap:Message1><multimap:Message2>" + note("Chris")
                + "</multimap:Message2><multimap:Message3>" + note("Tove") + "</multimap:Message3></multimap:Messages>",
                body(gather("differentXml", "combine", "", "", notes)));
    }

    @Test
    void concatenate() {
        assertEquals("a\nb\n", body(gather("plainText", "concatenate", "", "", List.of("a\n", "b\n"))));
        assertFalse(Json.bool(gather("sameXml", "concatenate", "", "", List.of("<a/>")), "ok", true));
        assertFalse(Json.bool(gather("plainText", "combine", "", "", List.of("a")), "ok", true));
    }

    @Test
    void combineAtXPathRebuildsASplitDocument() {
        String order = "<PurchaseOrder><Id>1</Id><Items><Item>a</Item><Item>b</Item><Item>c</Item></Items></PurchaseOrder>";
        Map<String, Object> split = new SplitStep().run(Json.o().with("splitter", "general").with("expressionType", "xpath")
                .with("expression", "/PurchaseOrder/Items/Item").with("input", Json.o().with("body", order)));
        List<String> parts = new ArrayList<>();
        for (Object m : Json.arr(split, "messages")) {
            parts.add((String) ((Map<String, Object>) m).get("body"));
        }
        assertEquals(3, parts.size());
        // The target defaults to the source's parent (/PurchaseOrder/Items).
        assertEquals("<?xml version='1.0' encoding='UTF-8'?>" + order,
                body(gather("sameXml", "combineAtXPath", "/PurchaseOrder/Items/Item", "", parts)));
        assertFalse(Json.bool(gather("sameXml", "combineAtXPath", "/PurchaseOrder/X", "", parts), "ok", true));
        assertFalse(Json.bool(gather("sameXml", "combineAtXPath", "/PurchaseOrder/Items/Item", "/Nope", parts), "ok", true));
        Map<String, Object> attr = gather("sameXml", "combineAtXPath", "/PurchaseOrder/Items/Item/@x", "/PurchaseOrder", List.of("<PurchaseOrder><Items><Item x='1'/></Items></PurchaseOrder>"));
        assertTrue(Json.arr(attr, "diagnostics").toString().contains("must select elements"));
        assertTrue(Json.arr(gather("any", "zip", "", "", List.of("a")), "diagnostics").toString().contains("UnsupportedInSimulator"));
        // An unknown value names the simulated ones (2026-11-06: 'xml-same' read as if it were Any).
        assertTrue(Json.arr(gather("xml-same", "combine", "", "", List.of("<a/>")), "diagnostics").toString().contains("simulated: sameXml, differentXml, plainText"));
        // Review 2026-11-03: a target among the gathered nodes lost them all, and a DOCTYPE ended up inside the wrapper.
        for (String target : List.of("/r/i", "/r/i/x")) {
            Map<String, Object> overlap = gather("sameXml", "combineAtXPath", "/r/i", target, List.of("<r><i><x/>A</i></r>"));
            assertFalse(Json.bool(overlap, "ok", true), () -> Json.write(overlap));
            assertTrue(Json.arr(overlap, "diagnostics").toString().contains("replaces"));
        }
        for (String format : List.of("sameXml", "differentXml")) {
            for (String msg : List.of("<?xml version='1.0'?><!-- c --><!DOCTYPE r [<!ENTITY e 'A'>]><r>&e;</r>",
                    "\uFEFF<!DOCTYPE r [<!ENTITY e 'A'>]><r>&e;</r>", "\uFEFF<?xml version='1.0'?><!DOCTYPE r><r/>")) {
                Map<String, Object> dtd = gather(format, "combine", "", "", List.of(msg));
                assertFalse(Json.bool(dtd, "ok", true), () -> Json.write(dtd));
                assertTrue(Json.arr(dtd, "diagnostics").toString().contains("UnsupportedInSimulator"));
            }
        }
        // A byte order mark alone is dropped like the declaration.
        assertTrue(body(gather("sameXml", "combine", "", "", List.of("\uFEFF<?xml version='1.0'?><r/>"))).contains("<multimap:Message1><r/>"));
    }

    @Test
    void combineAtXPathUsesLfOnEveryComputer() {
        String body = body(gather("sameXml", "combineAtXPath", "/note/to", "/note",
                List.of(note("Jack"), note("Chris"))));
        assertFalse(body.contains("\r"), body);
        assertTrue(body.contains("\n<from>Jill</from>"), body);
    }

    /** An unnamespaced <html> root stays XML: the JDK's serializer would otherwise switch to its HTML method. */
    @Test
    void combineAtXPathWritesXmlForAnHtmlRoot() {
        String html = "<html><head/><body><br/><item>1</item></body></html>";
        String body = body(gather("sameXml", "combineAtXPath", "/html/body/item", "", List.of(html, html.replace(">1<", ">2<"))));
        assertTrue(body.contains("<br/>") && !body.contains("<META"), body);
        assertTrue(body.contains("<item>1</item><item>2</item>"), body);
    }

    /** SAP shows no Combine of a body that is not XML; the result stays as before, with a warning (Q-F1). */
    @Test
    void combineWarnsAboutABodyThatIsNotXml() {
        Map<String, Object> r = gather("sameXml", "combine", "", "", List.of("<a/>", "{\"sku\":\"M-200\"}"));
        assertTrue(Json.bool(r, "ok", false));
        assertTrue(body(r).contains("<multimap:Message1><a/>{\"sku\":\"M-200\"}</multimap:Message1>"), body(r));
        // JSON is character data: the message is no XML document, but the combined result is well-formed XML.
        assertEquals(1, Json.arr(r, "diagnostics").size(), String.valueOf(r.get("diagnostics")));
        assertTrue(Json.arr(r, "diagnostics").toString().contains("Message 2 is not a well-formed XML document"));
        assertTrue(Json.arr(gather("sameXml", "combine", "", "", List.of("<a/>", "<b/>")), "diagnostics").isEmpty());
        // A text with a bare & breaks the result too, and the warning says so.
        Map<String, Object> amp = gather("sameXml", "combine", "", "", List.of("<a/>", "a & b"));
        assertEquals(2, Json.arr(amp, "diagnostics").size(), String.valueOf(amp.get("diagnostics")));
        assertTrue(Json.arr(amp, "diagnostics").toString().contains("The combined result is not well-formed XML"));
    }
}
