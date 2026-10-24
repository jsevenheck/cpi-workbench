package io.cpiwb.xml;

import io.cpiwb.core.Json;
import io.cpiwb.xml.compat.SaxonCompat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class XsltStepTest {

    private final XsltStep step = new XsltStep();

    private static final String CPI_XSL = String.join("\n",
            "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'",
            "    xmlns:hci='http://sap.com/it/' exclude-result-prefixes='hci'>",
            "  <xsl:param name='exchange'/>",
            "  <xsl:param name='Shared'/>",
            "  <xsl:param name='OnlyProp'/>",
            "  <xsl:param name='Missing' select=\"'dflt'\"/>",
            "  <xsl:template match='/'>",
            "    <xsl:value-of select=\"hci:setHeader($exchange, 'Count', string(count(//i)))\"/>",
            "    <xsl:value-of select=\"hci:setProperty($exchange, 'Done', 'yes')\"/>",
            "    <r shared='{$Shared}' prop='{$OnlyProp}' missing='{$Missing}'/>",
            "  </xsl:template>",
            "</xsl:stylesheet>");

    static Map<String, Object> input(String body, List<Object> headers, List<Object> properties) {
        return Json.o().with("body", body).with("headers", headers).with("properties", properties);
    }

    static Map<String, Object> hv(String name, String value, String type) {
        return Json.o().with("name", name).with("value", value).with("type", type);
    }

    private Map<String, Object> run(String xsl, Map<String, Object> input, Map<String, Object> options) {
        return step.run(Json.o().with("xslt", Json.o().with("content", xsl).with("path", "m.xsl"))
                .with("input", input).with("options", options));
    }

    private static String body(Map<String, Object> r) {
        return Json.str(Json.obj(r, "output"), "body", null);
    }

    private static String value(Map<String, Object> r, String list, String name) {
        for (Object o : Json.arr(Json.obj(r, "output"), list)) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (name.equals(m.get("name"))) {
                return (String) m.get("value");
            }
        }
        return null;
    }

    @Test
    void bindsHeadersAndPropertiesAndAppliesCpiFunctions() {
        Map<String, Object> r = run(CPI_XSL, input("<x><i/><i/><i/></x>",
                List.of(hv("Shared", "fromHeader", "String")),
                List.of(hv("Shared", "fromProperty", "String"), hv("OnlyProp", "p", "String"))), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertTrue(body(r).contains("shared=\"fromHeader\""), body(r));
        assertTrue(body(r).contains("prop=\"p\""));
        assertTrue(body(r).contains("missing=\"dflt\""));
        assertEquals("3", value(r, "headers", "Count"));
        assertEquals("yes", value(r, "properties", "Done"));
        Map<String, Object> changes = Json.obj(Json.obj(r, "changes"), "headers");
        assertEquals(List.of("Count"), changes.get("added"));
        assertEquals(2, Json.arr(r, "cpiCalls").size());
    }

    @Test
    void reportsParameterBindings() {
        Map<String, Object> r = run(CPI_XSL, input("<x/>", List.of(hv("Shared", "h", "String")),
                List.of(hv("OnlyProp", "p", "String"))), null);
        Map<String, String> sources = new java.util.TreeMap<>();
        for (Object o : Json.arr(r, "parameters")) {
            Map<String, Object> m = (Map<String, Object>) o;
            sources.put((String) m.get("name"), (String) m.get("source"));
        }
        assertEquals(Map.of("exchange", "exchange", "Shared", "header", "OnlyProp", "property", "Missing", "default"),
                sources);
    }

    @Test
    void propertyPrecedenceIsConfigurable() {
        Map<String, Object> r = run(CPI_XSL, input("<x/>", List.of(hv("Shared", "h", "String")),
                List.of(hv("Shared", "p", "String"))), Json.o().with("paramPrecedence", "property"));
        assertTrue(body(r).contains("shared=\"p\""), body(r));
    }

    @Test
    void strictMissingParameterMode() {
        Map<String, Object> r = run(CPI_XSL, input("<x/>", List.of(), List.of()),
                Json.o().with("missingParameter", "error"));
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("Header/Property value not found for the parameter"));
    }

    @Test
    void typedHeaderReachesXsltAsTypedValue() {
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:param name='n'/><xsl:output method='text'/>"
                + "<xsl:template match='/'><xsl:value-of select='$n + 1, $n instance of xs:integer'"
                + " xmlns:xs='http://www.w3.org/2001/XMLSchema'/></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(hv("n", "41", "Integer")), List.of()), null);
        assertEquals("42 true", body(r));
    }

    @Test
    void setHeaderWithoutExchangeFails() {
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform' xmlns:hci='http://sap.com/it/'>"
                + "<xsl:template match='/'><xsl:value-of select=\"hci:setHeader('x', 'a', 'b')\"/></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("must be $exchange"), Json.write(r));
    }

    @Test
    void compileErrorHasLineNumber() {
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>\n"
                + "<xsl:template match='/'>\n<xsl:value-of select='1 +'/>\n</xsl:template>\n</xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("compile", r.get("failedPhase"));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertEquals(3L, ((Number) d.get("line")).longValue());
    }

    @Test
    void runtimeErrorAndTerminatingMessage() {
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:template match='/'><xsl:message terminate='yes'>stop here</xsl:message></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("runtime", r.get("failedPhase"));
        assertTrue(Json.write(Json.arr(r, "messages")).contains("stop here"));
    }

    @Test
    void invalidInputXmlIsReported() {
        Map<String, Object> r = run(CPI_XSL, input("<x>", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
    }

    @Test
    void stylesheetIsCached() {
        Map<String, Object> in = input("<x/>", List.of(), List.of());
        run(CPI_XSL, in, null);
        Map<String, Object> r = run(CPI_XSL, in, null);
        assertTrue(Json.bool(Json.obj(r, "timing"), "cached", false));
    }

    @Test
    void includesResolveFromResourcesAndProjectButNotOutside(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Files.createDirectories(project.resolve("mappings"));
        Files.writeString(project.resolve("mappings/lib.xsl"),
                "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                        + "<xsl:template name='t'>from-disk</xsl:template></xsl:stylesheet>");
        Files.writeString(dir.resolve("secret.xsl"), "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'/>");
        String main = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:include href='%s'/><xsl:output method='text'/>"
                + "<xsl:template match='/'><xsl:call-template name='t'/></xsl:template></xsl:stylesheet>";

        Map<String, Object> fromDisk = step.run(Json.o()
                .with("xslt", Json.o().with("content", String.format(main, "lib.xsl")).with("path", "mappings/main.xsl"))
                .with("baseDir", project.toString()).with("input", input("<x/>", List.of(), List.of())));
        assertEquals("from-disk", body(fromDisk), () -> Json.write(fromDisk));

        Map<String, Object> fromBuffer = step.run(Json.o()
                .with("xslt", Json.o().with("content", String.format(main, "lib.xsl")).with("path", "mappings/main.xsl"))
                .with("baseDir", project.toString())
                .with("resources", Json.o().with("mappings/lib.xsl",
                        "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                                + "<xsl:template name='t'>from-buffer</xsl:template></xsl:stylesheet>"))
                .with("input", input("<x/>", List.of(), List.of())));
        assertEquals("from-buffer", body(fromBuffer));

        Map<String, Object> outside = step.run(Json.o()
                .with("xslt", Json.o().with("content", String.format(main, "../../secret.xsl")).with("path", "mappings/main.xsl"))
                .with("baseDir", project.toString()).with("input", input("<x/>", List.of(), List.of())));
        assertFalse(Json.bool(outside, "ok", true));
        assertTrue(Json.write(outside).contains("outside the project"), Json.write(outside));
    }

    @Test
    void higherOrderFunctionsDependOnProfile() {
        String xsl = "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:output method='text'/><xsl:template match='/'>"
                + "<xsl:value-of select='for-each((1,2,3), function($x) { $x * 2 })'/></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(), List.of()), null);
        if ("saxon-he-9.9".equals(SaxonCompat.PROFILE)) {
            // Saxon-HE 9.9 lacks higher-order functions (CPI's Saxon-EE 9.9 has them) - finding F14.
            assertFalse(Json.bool(r, "ok", true));
        } else {
            assertEquals("2 4 6", body(r));
        }
    }

    @Test
    void outputEncodingIsHonoured() {
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:output encoding='ISO-8859-1'/><xsl:template match='/'><r>äöü</r></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = run(xsl, input("<x/>", List.of(), List.of()), null);
        assertTrue(body(r).contains("<r>äöü</r>"), body(r));
        assertEquals("ISO-8859-1", Json.str(Json.obj(r, "output"), "encoding", null));
    }
}
