package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Message Digest and Encoder (docs/plan.md F62, F63). Expected canonical forms are the W3C's: the document of
 * Canonical XML 1.0, section 3.3, and what the specifications say about document subsets. Expected digests are
 * computed here from the expected canonical text, not taken from the step. The Base64 example is SAP's.
 */
class DigestEncoderStepTest {

    /** Canonical XML 1.0, 3.3 "Start and End Tags", with a comment after the root. */
    static final String W3C_INPUT = "<!DOCTYPE doc [<!ATTLIST e9 attr CDATA \"default\">]>\n<doc>\n   <e1   />\n   <e2   ></e2>\n"
            + "   <e3   name = \"elem3\"   id=\"elem3\"   />\n   <e4   name=\"elem4\"   id=\"elem4\"   ></e4>\n"
            + "   <e5 a:attr=\"out\" b:attr=\"sorted\" attr2=\"all\" attr=\"I'm\"\n      xmlns:b=\"http://www.ietf.org\"\n"
            + "      xmlns:a=\"http://www.w3.org\"\n      xmlns=\"http://example.org\"/>\n   <e6 xmlns=\"\" xmlns:a=\"http://www.w3.org\">\n"
            + "      <e7 xmlns=\"http://www.ietf.org\">\n         <e8 xmlns=\"\" xmlns:a=\"http://www.w3.org\">\n"
            + "            <e9 xmlns=\"\" xmlns:a=\"http://www.ietf.org\"/>\n         </e8>\n      </e7>\n   </e6>\n</doc><!-- c -->";
    static final String W3C_CANONICAL = "<doc>\n   <e1></e1>\n   <e2></e2>\n   <e3 id=\"elem3\" name=\"elem3\"></e3>\n"
            + "   <e4 id=\"elem4\" name=\"elem4\"></e4>\n   <e5 xmlns=\"http://example.org\" xmlns:a=\"http://www.w3.org\" "
            + "xmlns:b=\"http://www.ietf.org\" attr=\"I'm\" attr2=\"all\" b:attr=\"sorted\" a:attr=\"out\"></e5>\n"
            + "   <e6 xmlns:a=\"http://www.w3.org\">\n      <e7 xmlns=\"http://www.ietf.org\">\n         <e8 xmlns=\"\">\n"
            + "            <e9 xmlns:a=\"http://www.ietf.org\" attr=\"default\"></e9>\n         </e8>\n      </e7>\n   </e6>\n</doc>";
    static final String ORDER = "<o:Order xmlns:o='urn:o' xmlns:x='urn:unused' xml:lang='de'><o:Item b='2' a='1'>t</o:Item><o:Item/></o:Order>";

    static Map<String, Object> digest(String body, Json.Obj settings) {
        return new DigestStep().run(settings.with("input", Json.o().with("body", body)
                .with("headers", List.of(Json.o().with("name", "Keep").with("value", "1").with("type", "String")))));
    }

    static String header(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        return Json.str(Json.obj(r, "header"), "value", null);
    }

    static String hash(String algorithm, String text) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String error(Map<String, Object> r) {
        assertFalse(Json.bool(r, "ok", true), () -> Json.write(r));
        return Json.write(r.get("diagnostics"));
    }

    @Test
    void canonicalFormsAreTheW3Cs() throws Exception {
        Map<String, Object> r = digest(W3C_INPUT, Json.o().with("canonicalization", "c14n10").with("algorithm", "SHA256"));
        assertEquals(W3C_CANONICAL, r.get("canonical"));
        assertEquals(hash("SHA-256", W3C_CANONICAL), header(r));
        assertEquals(W3C_CANONICAL + "\n<!-- c -->", digest(W3C_INPUT, Json.o().with("canonicalization", "c14n10Comments")).get("canonical"));
        assertEquals(W3C_CANONICAL, digest(W3C_INPUT, Json.o().with("canonicalization", "c14n11")).get("canonical"));
        // Exclusive: a namespace is written where it is used, so the declarations nothing uses are gone.
        String exclusive = (String) digest(W3C_INPUT, Json.o().with("canonicalization", "exclusive")).get("canonical");
        assertTrue(exclusive.contains("<e6>") && exclusive.contains("<e9 attr=\"default\"></e9>"), exclusive);
        // The W3C's identifier is taken as well as the tab's name.
        assertEquals(header(r), header(digest(W3C_INPUT, Json.o().with("canonicalization", "http://www.w3.org/TR/2001/REC-xml-c14n-20010315")
                .with("algorithm", "SHA-256"))));
    }

    @Test
    void theDigestGoesToTheHeaderAndTheBodyStays() throws Exception {
        Map<String, Object> r = digest("<a  y='2' x=\"1\"/>", Json.o());
        // SAP's defaults: SHA512, header SAPMessageDigest, the Base64-encoded digest.
        assertEquals("SAPMessageDigest", Json.str(Json.obj(r, "header"), "name", null));
        assertEquals(hash("SHA-512", "<a x=\"1\" y=\"2\"></a>"), header(r));
        Map<String, Object> out = Json.obj(r, "output");
        assertEquals("<a  y='2' x=\"1\"/>", out.get("body"), "SAP: the original message remains unchanged");
        String headers = Json.write(out.get("headers"));
        assertTrue(headers.contains("\"Keep\"") && headers.contains("\"SAPMessageDigest\"") && headers.contains(header(r)), headers);
        // What canonicalization is for: the same document written another way has the same digest.
        assertEquals(header(r), header(digest("<a x='1'   y='2'></a>", Json.o())));
        assertFalse(header(r).equals(header(digest("<a x='1' y='3'/>", Json.o()))));
        assertEquals("MyDigest", Json.str(Json.obj(digest("<a/>", Json.o().with("targetHeader", " MyDigest ")), "header"), "name", null));
        // An exported iFlow writes SHA-512, SAP's documentation SHA512.
        assertEquals(header(digest("<a/>", Json.o().with("algorithm", "SHA-512"))), header(digest("<a/>", Json.o().with("algorithm", "SHA512"))));
        assertEquals(hash("MD5", "<a></a>"), header(digest("<a/>", Json.o().with("algorithm", "MD5"))));
        assertEquals(hash("SHA-1", "<a></a>"), header(digest("<a/>", Json.o().with("algorithm", "SHA1"))));
        assertEquals(hash("SHA-384", "<a></a>"), header(digest("<a/>", Json.o().with("algorithm", "SHA384"))));
    }

    @Test
    void aFilterTakesOneElementWithWhatItInherits() throws Exception {
        Json.Obj ns = Json.o().with("o", "urn:o");
        Map<String, Object> inclusive = digest(ORDER, Json.o().with("filter", "/o:Order/o:Item[1]").with("namespaces", ns));
        // A document subset in Canonical XML: the namespaces in scope and the inherited xml: attributes belong to it.
        String expected = "<o:Item xmlns:o=\"urn:o\" xmlns:x=\"urn:unused\" a=\"1\" b=\"2\" xml:lang=\"de\">t</o:Item>";
        assertEquals(expected, inclusive.get("canonical"));
        assertEquals(hash("SHA-512", expected), header(inclusive));
        assertEquals("<o:Item xmlns:o=\"urn:o\" a=\"1\" b=\"2\">t</o:Item>",
                digest(ORDER, Json.o().with("filter", "/o:Order/o:Item[1]").with("namespaces", ns).with("canonicalization", "exclusive")).get("canonical"));
        assertEquals(digest(ORDER, Json.o()).get("canonical"), digest(ORDER, Json.o().with("filter", "/").with("namespaces", ns)).get("canonical"));
    }

    @Test
    void whatIsNotKnownIsRefusedOrLabelled() throws Exception {
        Json.Obj ns = Json.o().with("o", "urn:o");
        for (String filter : List.of("/o:Order/o:Item", "/o:Order/o:Missing", "/o:Order/o:Item[1]/@a", "/o:Order/o:Item[1]/text()")) {
            String e = error(digest(ORDER, Json.o().with("filter", filter).with("namespaces", ns)));
            assertTrue(e.contains("UnsupportedInSimulator") && e.contains("Q-E1"), e);
        }
        assertTrue(error(digest(ORDER, Json.o().with("filter", "/o:Order").with("namespaces", ns).with("canonicalization", "none")))
                .contains("UnsupportedInSimulator"));
        assertTrue(error(digest(ORDER, Json.o().with("filter", "count(/*)"))).contains("selects nodes"));
        // None: the bytes as they are, said to be an assumption; a body that is not XML also gets SAP's sentence.
        Map<String, Object> none = digest("<a  x='1'/>", Json.o().with("canonicalization", "none").with("algorithm", "MD5"));
        assertEquals(hash("MD5", "<a  x='1'/>"), header(none));
        assertNull(none.get("canonical"));
        assertTrue(Json.write(none.get("diagnostics")).contains("assumption") && !Json.write(none.get("diagnostics")).contains("warning"));
        Map<String, Object> text = digest("abc", Json.o().with("canonicalization", "none").with("algorithm", "MD5"));
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", header(text), "MD5 of abc (RFC 1321), Base64");
        assertTrue(Json.write(text.get("diagnostics")).contains("warning") && Json.write(text.get("diagnostics")).contains("only process XML content"));
        assertTrue(error(digest("abc", Json.o())).contains("only process XML content"));
        assertTrue(error(digest("<a/>", Json.o().with("targetHeader", " "))).contains("Target Header Name"));
        assertTrue(error(digest("<a/>", Json.o().with("algorithm", "SHA3"))).contains("Unknown Digest Algorithm"));
        assertTrue(error(digest("<a/>", Json.o().with("canonicalization", "xml-c14n"))).contains("Unknown Canonicalization Method"));
    }

    static Map<String, Object> encode(String body, String scheme) {
        return new EncoderStep().run(Json.o().with("scheme", scheme).with("input", Json.o().with("body", body)), false);
    }

    static Map<String, Object> decode(Map<String, Object> input, String scheme) {
        return new EncoderStep().run(Json.o().with("scheme", scheme).with("input", input), true);
    }

    /** A step's output as the next step's input: the text, or the bytes of a binary body. */
    static Map<String, Object> output(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        return Json.obj(r, "output");
    }

    @Test
    void base64EncodeIsSapsExampleInCamelsLines() {
        // SAP's example; the line end after it is Camel's (a documentation page would not show it).
        Map<String, Object> r = encode("<message>\r\n\tInput for encoder\r\n</message>\r\n", "base64Encode");
        assertEquals("PG1lc3NhZ2U+DQoJSW5wdXQgZm9yIGVuY29kZXINCjwvbWVzc2FnZT4NCg==\r\n", r.get("body"));
        assertTrue(Json.write(r.get("diagnostics")).contains("Camel 3.14.7") && Json.write(r.get("diagnostics")).contains("Q-E1"), Json.write(r));
        // Lines of 76 characters, each ended by CR LF: 100 bytes are 136 characters, 76 and 60.
        String longer = (String) encode("x".repeat(100), "base64Encode").get("body");
        assertEquals(List.of(76, 60), java.util.Arrays.stream(longer.split("\r\n")).map(String::length).toList());
        assertTrue(longer.endsWith("\r\n") && longer.length() == 136 + 4, longer);
        // A last line that is full gets one line end, not two.
        assertEquals(76 + 2, ((String) encode("x".repeat(57), "base64Encode").get("body")).length());
        assertEquals("", encode("", "base64Encode").get("body"));
        // The body's bytes: UTF-8, or the encoding its XML declaration names.
        assertEquals("w6Q=\r\n", encode("ä", "base64Encode").get("body"));
        byte[] bytes = Base64.getMimeDecoder().decode((String) encode("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ä</a>", "base64Encode").get("body"));
        assertEquals((byte) 0xE4, bytes[bytes.length - 5]);
    }

    @Test
    void base64DecodeIsLenientAsCamels() {
        String text = "Grüße aus Köln, " + "x".repeat(80);
        assertEquals(text, decode(output(encode(text, "base64Encode")), "base64Decode").get("body"), "wrapped input");
        assertEquals("abc", decode(Json.o().with("body", "YWJj"), "base64Decode").get("body"));
        assertEquals("a", decode(Json.o().with("body", "YQ"), "base64Decode").get("body"), "padding is not required");
        // Not clean Base64: nothing fails, as with Camel's decoder; the result says what was skipped.
        Map<String, Object> dirty = decode(Json.o().with("body", "YW*Jj"), "base64Decode");
        assertEquals("abc", dirty.get("body"));
        assertTrue(Json.write(dirty.get("diagnostics")).contains("1 character(s) outside the alphabet skipped"), Json.write(dirty));
        Map<String, Object> twice = decode(Json.o().with("body", "YQ==YWJj"), "base64Decode");
        assertEquals("a", twice.get("body"), "the text ends at the first =");
        assertTrue(Json.write(twice.get("diagnostics")).contains("4 character(s) after the first '=' ignored"), Json.write(twice));
        Map<String, Object> odd = decode(Json.o().with("body", "YWJjZ"), "base64Decode");
        assertEquals("abc", odd.get("body"));
        assertTrue(Json.write(odd.get("diagnostics")).contains("one character left over"), Json.write(odd));
        assertFalse(Json.write(decode(Json.o().with("body", "YWJj\r\n"), "base64Decode").get("diagnostics")).contains("warning"));
    }

    @Test
    void aResultThatIsNoTextIsGivenAsBytes() {
        // 0x00 0xFF 0x10: not UTF-8, and a NUL.
        Map<String, Object> r = decode(Json.o().with("body", "AP8Q"), "base64Decode");
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertNull(r.get("body"));
        assertEquals(true, r.get("binary"));
        assertEquals(3, ((Number) r.get("bodySize")).intValue());
        assertEquals("00000000  00 ff 10                                         |...|\n", r.get("preview"));
        Map<String, Object> out = Json.obj(r, "output");
        assertEquals("AP8Q", out.get("bodyBase64"));
        assertNull(out.get("body"));
        // The bytes are the next step's input.
        assertEquals("AP8Q\r\n", new EncoderStep().run(Json.o().with("scheme", "base64Encode").with("input", out), false).get("body"));
        // Text with a NUL is no text either; plain text stays text.
        assertEquals(true, decode(Json.o().with("body", "YQBi"), "base64Decode").get("binary"));
        assertNull(decode(Json.o().with("body", "YWJj"), "base64Decode").get("binary"));
    }

    @Test
    void gzipHasCamelsHeaderAndComesBack() {
        String text = "<Order id=\"4711\">Müller</Order>";
        Map<String, Object> gz = encode(text, "gzipCompress");
        assertEquals(true, gz.get("binary"));
        byte[] bytes = Base64.getDecoder().decode((String) output(gz).get("bodyBase64"));
        // Magic, deflate, no flags, time 0, no extra flags, operating system 255: commons-compress's defaults.
        assertEquals("1f8b0800000000000" + "0ff", java.util.HexFormat.of().formatHex(bytes, 0, 10));
        assertTrue(((String) gz.get("preview")).startsWith("00000000  1f 8b 08 00"), (String) gz.get("preview"));
        Map<String, Object> back = decode(output(gz), "gzipDecompress");
        assertEquals(text, back.get("body"));
        assertTrue(error(decode(Json.o().with("body", "no gzip"), "gzipDecompress")).contains("GZIP"));
    }

    @Test
    void zipNamesItsEntryAsCamelDoes() throws Exception {
        String text = "<Order id=\"4711\"/>";
        Map<String, Object> named = new EncoderStep().run(Json.o().with("scheme", "zipCompress").with("input", Json.o().with("body", text)
                .with("headers", List.of(Json.o().with("name", "CamelFileName").with("value", "out/order.xml").with("type", "String"),
                        Json.o().with("name", "Keep").with("value", "1").with("type", "String")))), false);
        Map<String, Object> out = output(named);
        // The file name part names the entry; the header then names the archive.
        String headers = Json.write(out.get("headers"));
        assertTrue(headers.contains("\"order.xml.zip\"") && headers.contains("\"Keep\""), headers);
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(Base64.getDecoder().decode((String) out.get("bodyBase64"))))) {
            assertEquals("order.xml", zis.getNextEntry().getName());
            assertEquals(text, new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            assertNull(zis.getNextEntry());
        }
        Map<String, Object> back = decode(out, "zipDecompress");
        assertEquals(text, back.get("body"));
        assertTrue(Json.write(Json.obj(back, "output").get("headers")).contains("\"value\":\"order.xml\""), Json.write(back));
        // Without the header Camel takes the message id; the simulator makes one up and says so.
        Map<String, Object> unnamed = encode(text, "zipCompress");
        assertTrue(Json.write(unnamed.get("diagnostics")).contains("made one up"), Json.write(unnamed));
        assertTrue(Json.write(output(unnamed).get("headers")).matches(".*ID-cpi-workbench-\\d+\\.zip.*"), Json.write(unnamed));

        // Camel's two failures: an archive with a second entry, and a body that is no archive.
        java.io.ByteArrayOutputStream two = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(two)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("first.xml"));
            zos.write("<first/>".getBytes(StandardCharsets.UTF_8));
            zos.putNextEntry(new java.util.zip.ZipEntry("second.xml"));
            zos.write("<second/>".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(error(decode(Json.o().with("bodyBase64", Base64.getEncoder().encodeToString(two.toByteArray())), "zipDecompress"))
                .contains("Zip file has more than 1 entry."));
        assertTrue(error(decode(Json.o().with("body", "no zip"), "zipDecompress")).contains("Unable to unzip the file"));
    }

    @Test
    void otherSchemesAreRefused() {
        for (String scheme : List.of("mimeMultipartEncode", "base64Decode", "other")) {
            String e = error(encode("<a/>", scheme));
            assertTrue(e.contains("UnsupportedInSimulator") && e.contains(scheme), e);
        }
        for (String scheme : List.of("mimeMultipartDecode", "gzipCompress")) {
            assertTrue(error(decode(Json.o().with("body", "YQ=="), scheme)).contains("UnsupportedInSimulator"));
        }
    }

    @Test
    @SuppressWarnings("unchecked") // engine.call returns Object; the result is a JSON object
    void theEngineKnowsBothSteps() throws Exception {
        XmlEngine engine = new XmlEngine();
        assertTrue(Json.write(engine.describe().get("methods")).contains("\"encode.run\",\"decode.run\",\"digest.run\""));
        Map<String, Object> input = Json.o().with("body", "<a/>");
        assertEquals("PGEvPg==\r\n", ((Map<?, ?>) engine.call("encode.run", Json.o().with("scheme", "base64Encode").with("input", input))).get("body"));
        assertEquals("<a/>", ((Map<?, ?>) engine.call("decode.run", Json.o().with("scheme", "base64Decode").with("input", Json.o().with("body", "PGEvPg==")))).get("body"));
        assertEquals(hash("SHA-512", "<a></a>"), header((Map<String, Object>) engine.call("digest.run", Json.o().with("input", input))));
    }
}
