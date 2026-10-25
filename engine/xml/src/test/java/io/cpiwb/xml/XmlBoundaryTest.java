package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XML runs read files inside the project only: no external entities, doc() or unparsed-text() outside it. */
class XmlBoundaryTest {

    private static final String SECRET = "TOP-SECRET-42";

    @TempDir
    Path tmp;
    private Path project;
    private String secretUri;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(tmp.resolve("project"));
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, SECRET);
        secretUri = secret.toUri().toString();
        Files.writeString(project.resolve("inside.txt"), "inside-value");
        Files.writeString(project.resolve("inside.xml"), "<v>inside-doc</v>");
    }

    private String entityDoc() {
        return "<?xml version='1.0'?>\n<!DOCTYPE r [<!ENTITY x SYSTEM '" + secretUri + "'>]>\n<r>&x;</r>";
    }

    private static void assertNoLeak(Map<String, Object> r) {
        String json = Json.write(r);
        assertFalse(json.contains(SECRET), json);
    }

    private Map<String, Object> xslt(String xsl, String body) {
        return new XsltStep().run(Json.o().with("xslt", Json.o().with("content", xsl).with("path", "m.xsl"))
                .with("baseDir", project.toString()).with("input", Json.o().with("body", body)));
    }

    private Map<String, Object> xpath(String expr, String version, String body) {
        return new XPathStep().run(Json.o().with("expression", expr).with("version", version)
                .with("baseDir", project.toString()).with("input", Json.o().with("body", body)));
    }

    private static final String COPY = "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='.'/></xsl:template></xsl:stylesheet>";

    private static String select(String expr) {
        return "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:output method='text'/><xsl:template match='/'><xsl:value-of select=\"" + expr + "\"/></xsl:template></xsl:stylesheet>";
    }

    @Test
    void xsltInputExternalEntityIsBlocked() {
        Map<String, Object> r = xslt(COPY, entityDoc());
        assertNoLeak(r);
        assertFalse(Json.bool(r, "ok", true), () -> Json.write(r));
    }

    @Test
    void xsltInternalEntityStillWorks() {
        Map<String, Object> r = xslt(COPY, "<!DOCTYPE r [<!ENTITY x 'internal'>]><r>&x;</r>");
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("internal", Json.str(Json.obj(r, "output"), "body", null));
    }

    @Test
    void xsltStylesheetExternalEntityIsBlocked() {
        String xsl = "<!DOCTYPE xsl:stylesheet [<!ENTITY x SYSTEM '" + secretUri + "'>]>"
                + "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:output method='text'/><xsl:template match='/'>&x;</xsl:template></xsl:stylesheet>";
        assertNoLeak(xslt(xsl, "<a/>"));
    }

    @Test
    void xsltDocumentAndUnparsedTextOutsideAreBlocked() {
        for (String expr : List.of("doc('" + secretUri + "')", "unparsed-text('" + secretUri + "')",
                "unparsed-text-available('" + secretUri + "')", "string-join(unparsed-text-lines('" + secretUri + "'))")) {
            Map<String, Object> r = xslt(select(expr), "<a/>");
            assertNoLeak(r);
            String out = Json.str(Json.obj(r, "output"), "body", "");
            assertFalse(out.equals("true"), expr + ": " + Json.write(r));
        }
    }

    @Test
    void xsltReadsFilesInsideTheProject() {
        Map<String, Object> r = xslt(select("concat(unparsed-text('inside.txt'), '/', doc('inside.xml'))"), "<a/>");
        assertEquals("inside-value/inside-doc", Json.str(Json.obj(r, "output"), "body", null), () -> Json.write(r));
    }

    @Test
    void entityInADocumentLoadedByDocIsBlocked() throws Exception {
        Files.writeString(project.resolve("evil.xml"), entityDoc());
        assertNoLeak(xslt(select("doc('evil.xml')"), "<a/>"));
    }

    @Test
    void xpathInputExternalEntityIsBlocked() {
        for (String version : List.of("1.0", "3.1")) {
            Map<String, Object> r = xpath("string(/r)", version, entityDoc());
            assertNoLeak(r);
            assertFalse(Json.bool(r, "ok", true), () -> version + ": " + Json.write(r));
        }
    }

    @Test
    void xpathDocAndUnparsedTextOutsideAreBlocked() {
        for (String expr : List.of("string(doc('" + secretUri + "'))", "unparsed-text('" + secretUri + "')")) {
            Map<String, Object> r = xpath(expr, "3.1", "<a/>");
            assertNoLeak(r);
            assertFalse(Json.bool(r, "ok", true), () -> expr + ": " + Json.write(r));
        }
        Map<String, Object> inside = xpath("unparsed-text('inside.txt')", "3.1", "<a/>");
        assertTrue(Json.write(inside).contains("inside-value"), () -> Json.write(inside));
    }

    @Test
    void xsdInputExternalEntityIsBlocked() {
        String xsd = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:element name='r' type='xs:string'/></xs:schema>";
        Map<String, Object> r = new XsdStep().run(Json.o().with("schema", Json.o().with("content", xsd))
                .with("baseDir", project.toString()).with("input", Json.o().with("body", entityDoc())));
        assertNoLeak(r);
        assertFalse(Json.bool(r, "valid", true), () -> Json.write(r));
    }

    @Test
    void parseXmlCannotReadEntitiesOutside() {
        // The XML text is built in XPath, so no escaping is needed inside the attribute or the string.
        String text = "concat('&lt;!DOCTYPE r [&lt;!ENTITY x SYSTEM &quot;', $u, '&quot;&gt;]&gt;&lt;r&gt;&amp;x;&lt;/r&gt;')";
        for (String fn : List.of("parse-xml", "parse-xml-fragment")) {
            String xsl = "<xsl:stylesheet version='3.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                    + "<xsl:output method='text'/><xsl:variable name='u' select=\"'" + secretUri + "'\"/>"
                    + "<xsl:template match='/'><xsl:value-of select=\"string(" + fn + "(" + text + "))\"/></xsl:template></xsl:stylesheet>";
            assertNoLeak(xslt(xsl, "<a/>"));
            String xp = "let $u := '" + secretUri + "' return string(" + fn + "(concat('<!DOCTYPE r [<!ENTITY x SYSTEM \"', $u, '\">]><r>&x;</r>')))";
            Map<String, Object> r = xpath(xp, "3.1", "<a/>");
            assertNoLeak(r);
        }
        Map<String, Object> internal = xpath("string(parse-xml('<!DOCTYPE r [<!ENTITY x \"in\">]><r>&x;</r>'))", "3.1", "<a/>");
        assertTrue(Json.write(internal).contains("\"in\""), () -> Json.write(internal));
    }

    @Test
    void unparsedTextHonoursTheEncoding() throws Exception {
        Files.write(project.resolve("latin1.txt"), "Grüße".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Map<String, Object> r = xpath("unparsed-text('latin1.txt', 'iso-8859-1')", "3.1", "<a/>");
        assertTrue(Json.write(r).contains("Grüße"), () -> Json.write(r));
        Map<String, Object> utf8 = xpath("unparsed-text('inside.txt')", "3.1", "<a/>");
        assertTrue(Json.write(utf8).contains("inside-value"), () -> Json.write(utf8));
    }

    @Test
    void resultDocumentWritesNoFiles() {
        Path target = tmp.resolve("written.xml");
        String xsl = "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
                + "<xsl:template match='/'><xsl:result-document href='" + target.toUri() + "'><w/></xsl:result-document>"
                + "<main/></xsl:template></xsl:stylesheet>";
        Map<String, Object> r = xslt(xsl, "<a/>");
        assertFalse(Files.exists(target), () -> Json.write(r));
        assertFalse(Json.bool(r, "ok", true), () -> Json.write(r));
        assertTrue(Json.write(r).contains("UnsupportedInSimulator"), () -> Json.write(r));
    }

    @Test
    void formatDoesNotExpandExternalEntities() {
        assertNoLeak(new XPathStep().format(Json.o().with("text", entityDoc())));
    }
}
