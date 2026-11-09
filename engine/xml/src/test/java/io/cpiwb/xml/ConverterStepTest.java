package io.cpiwb.xml;

import io.cpiwb.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expected results are SAP's: the converter examples with and without namespace mapping, the conversion rules,
 * "How Streaming in the XML-to-JSON Converter Works", the JSON message without root element, and the CSV examples
 * ("Use the CSV to XML Converter", "Use the XML to CSV Converter"). SAP's examples have a typo (<E>valueE<E>);
 * the well-formed form is used.
 */
class ConverterStepTest {

    static final String NS_XML = "<?xml version='1.0' encoding='UTF-8'?>\n<n1:A xmlns:n1=\"http://com.sap/abc\" "
            + "xmlns:n2=\"http://com.sap/xyz\">\n<n2:B n2:attr1=\"1\" attr2=\"2\">valueB</n2:B>\n<C>valueC1</C>\n"
            + "<C>valueC2</C>\n<D/>\n<E>valueE</E>\n</n1:A>";
    static final List<Object> MAPPING = List.of(Json.o().with("prefix", "abc").with("namespace", "http://com.sap/abc"),
            Json.o().with("prefix", "xyz").with("namespace", "http://com.sap/xyz"));

    static Map<String, Object> convert(String conversion, String body, Json.Obj settings) {
        return new ConverterStep().run(settings.with("conversion", conversion).with("input", Json.o().with("body", body)));
    }

    static String body(Map<String, Object> r) {
        assertTrue(Json.bool(r, "ok", false), () -> String.valueOf(r.get("diagnostics")));
        return (String) r.get("body");
    }

    static String error(Map<String, Object> r) {
        assertFalse(Json.bool(r, "ok", true), () -> "expected an error, got " + r.get("body"));
        return Json.write(r.get("diagnostics"));
    }

    // ---------------------------------------------------------------- XML to JSON

    @Test
    void xmlToJsonWithNamespaceMapping() {
        assertEquals("{\"abc:A\":{\"xyz:B\":{\"@xyz:attr1\":\"1\",\"@attr2\":\"2\",\"$\":\"valueB\"},\"C\":[\"valueC1\",\"valueC2\"],"
                + "\"D\":\"\",\"E\":\"valueE\"}}", body(convert("xmlToJson", NS_XML,
                Json.o().with("useNamespaces", true).with("separator", ":").with("namespaceMapping", MAPPING))));
    }

    @Test
    void xmlToJsonWithoutNamespaceMapping() {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n<abc:A xmlns:abc=\"http://com.sap/abc\" xmlns:xyz=\"http://com.sap/xyz\">"
                + "<xyz:B xyz:attr1=\"1\" attr2=\"2\">valueB</xyz:B><C>valueC1</C><C>valueC2</C><D/><E>valueE</E></abc:A>";
        assertEquals("{\"A\":{\"B\":{\"@attr1\":\"1\",\"@attr2\":\"2\",\"$\":\"valueB\"},\"C\":[\"valueC1\",\"valueC2\"],"
                + "\"D\":\"\",\"E\":\"valueE\"}}", body(convert("xmlToJson", xml, Json.o())));
    }

    @Test
    void xmlToJsonRules() {
        // Siblings of one name become one array; the order of the others is not kept (non-streaming).
        assertEquals("{\"root\":{\"childA\":[\"A1\",\"A2\"],\"childB\":\"B\"}}",
                body(convert("xmlToJson", "<root><childA>A1</childA><childB>B</childB><childA>A2</childA></root>", Json.o())));
        // A white space value of a simple element is kept.
        assertEquals("{\"A\":\" \"}", body(convert("xmlToJson", "<A> </A>", Json.o())));
        // Mixed content: SAP's documented wrong result, with a warning.
        Map<String, Object> mixed = convert("xmlToJson", "<A>mixed1_value<B>valueB</B>mixed2_value</A>", Json.o());
        assertEquals("{\"A\":{\"B\":\"valueB\",\"$\":\"mixed1_valuemixed2_value\"}}", body(mixed));
        assertTrue(Json.write(mixed.get("diagnostics")).contains("mixed content"));
        // Comments and processing instructions are ignored; values are strings.
        assertEquals("{\"a\":{\"n\":\"5\"}}", body(convert("xmlToJson", "<a><!-- c --><?pi x?><n>5</n></a>", Json.o())));
    }

    @Test
    void xmlToJsonNamespacesNotInTheMapping() {
        Json.Obj ns = Json.o().with("useNamespaces", true).with("separator", ".").with("namespaceMapping", List.of());
        // With an XML prefix, the prefix is the JSON prefix.
        assertEquals("{\"ns.A\":\"\"}", body(convert("xmlToJson", "<ns:A xmlns:ns=\"http://test\"/>", ns)));
        // Without a prefix: SAP's exception; streaming ignores the namespace.
        assertTrue(error(convert("xmlToJson", "<A xmlns=\"http://test\">v</A>", ns)).contains("Invalid JSON namespace: http://test"));
        assertEquals("{\"A\":\"v\"}", body(convert("xmlToJson", "<A xmlns=\"http://test\">v</A>",
                Json.o().with("useNamespaces", true).with("streaming", true).with("streamingElements", "specific"))));
    }

    @Test
    void xmlToJsonStreaming() {
        String xml = "<root><A>a</A><B>b1</B><B>b2</B><C>c</C></root>";
        assertEquals("{\"root\":{\"A\":\"a\",\"B\":[\"b1\",\"b2\"],\"C\":\"c\"}}", body(convert("xmlToJson", xml, Json.o())));
        assertEquals("{\"root\":[{\"A\":[\"a\"],\"B\":[\"b1\",\"b2\"],\"C\":[\"c\"]}]}",
                body(convert("xmlToJson", xml, Json.o().with("streaming", true).with("streamingElements", "all"))));
        assertEquals("{\"root\":{\"A\":[\"a\"],\"B\":[\"b1\",\"b2\"],\"C\":\"c\"}}", body(convert("xmlToJson", xml,
                Json.o().with("streaming", true).with("streamingElements", "specific").with("arrayPaths", "/root/A\n/root/B"))));
        // Only root/A as array: SAP's invalid JSON with B twice.
        Map<String, Object> invalid = convert("xmlToJson", xml,
                Json.o().with("streaming", true).with("streamingElements", "specific").with("arrayPaths", List.of("root/A")));
        assertEquals("{\"root\":{\"A\":[\"a\"],\"B\":\"b1\",\"B\":\"b2\",\"C\":\"c\"}}", body(invalid));
        assertTrue(Json.write(invalid.get("diagnostics")).contains("duplicate member"));
        // Streaming keeps the order: childA, childB, childA.
        assertEquals("{\"root\":{\"childA\":[\"A1\"],\"childB\":\"B\",\"childA\":[\"A2\"]}}", body(convert("xmlToJson",
                "<root><childA>A1</childA><childB>B</childB><childA>A2</childA></root>",
                Json.o().with("streaming", true).with("streamingElements", "specific").with("arrayPaths", "/root/childA"))));
    }

    @Test
    void xmlToJsonWithoutRootElement() {
        Json.Obj suppress = Json.o().with("suppressRoot", true);
        assertEquals("{\"A\":\"a\",\"B\":\"b\",\"C\":\"c\",\"D\":\"d\"}", body(convert("xmlToJson",
                "<root>\n\t<A>a</A>\n\t<B>b</B>\n\t<C>c</C>\n\t<D>d</D>\n</root>", suppress)));
        assertEquals("\"v\"", body(convert("xmlToJson", "<root>v</root>", suppress)));
        assertEquals("\"\"", body(convert("xmlToJson", "<root></root>", suppress)));
        assertEquals("{\"A\":[\"a\"],\"B\":[{\"C\":[\"c\"]}]}", body(convert("xmlToJson", "<root><A>a</A><B><C>c</C></B></root>",
                Json.o().with("suppressRoot", true).with("streaming", true).with("streamingElements", "all"))));
    }

    @Test
    void xmlToJsonOutputEncoding() {
        Map<String, Object> r = new ConverterStep().run(Json.o().with("conversion", "xmlToJson").with("input", Json.o()
                .with("body", "<a/>").with("headers", List.of(Json.o().with("name", "CamelCharsetName").with("value", "ISO-8859-1")))));
        assertEquals("ISO-8859-1", r.get("encoding"));
        assertEquals("UTF-8", convert("xmlToJson", "<a/>", Json.o()).get("encoding"));
    }

    // ---------------------------------------------------------------- JSON to XML

    @Test
    void jsonToXmlWithNamespaceMapping() {
        String json = "{\"abc:A\":{\"xyz:B\":{\"@xyz:attr1\":\"1\",\"@attr2\":\"2\",\"$\":\"valueB\"},\"C\":[\"valueC1\",\"valueC2\"],"
                + "\"D\":\"\",\"E\":\"valueE\"}}";
        // SAP's example writes "D":"" as <D/>; a tenant writes <D></D> (docs/plan.md F56).
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><abc:A xmlns:abc=\"http://com.sap/abc\" xmlns:xyz=\"http://com.sap/xyz\">"
                + "<xyz:B xyz:attr1=\"1\" attr2=\"2\">valueB</xyz:B><C>valueC1</C><C>valueC2</C><D></D><E>valueE</E></abc:A>",
                body(convert("jsonToXml", json, Json.o().with("useNamespaces", true).with("separator", ":")
                        .with("namespaceMapping", "abc = http://com.sap/abc\nxyz = http://com.sap/xyz"))));
    }

    @Test
    void jsonToXmlRules() {
        // null is an empty element, "" a start and end tag (a tenant, F56); numbers keep their text; attributes come first.
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><parent attr1=\"123\" attr2=\"1234\"><id>000039002</id><href></href>"
                + "<externalId>Test_3</externalId><C/><n>1.50</n><t>true</t></parent>", body(convert("jsonToXml",
                "{\"parent\":{\"@attr1\":123,\"@attr2\":1234,\"id\":\"000039002\",\"href\":\"\",\"externalId\":\"Test_3\","
                        + "\"C\":null,\"n\":1.50,\"t\":true}}", Json.o())));
        assertTrue(error(convert("jsonToXml", "{\"parent\":{\"id\":\"1\",\"@attr1\":123}}", Json.o()))
                .contains("Trying to write an attribute when there is no open start element"));
        assertTrue(error(convert("jsonToXml", "{\"@attr1\":123,\"test\":1234}", Json.o()))
                .contains("The root member name starts with the attribute indicator @. An attribute cannot be root."));
        assertTrue(error(convert("jsonToXml", "{\"@attr1\":123}", Json.o().with("addRootElement", true).with("rootElementName", "r")))
                .contains("An attribute cannot be root"), "also with Add XML Root Element (SAP)");
        assertTrue(error(convert("jsonToXml", "{\"a\":{\"@x\":null}}", Json.o())).contains("JsonXmlException"));
        assertTrue(error(convert("jsonToXml", "[{\"a\":{\"b\":\"bvalue\"}}]", Json.o())).contains("must start with an object"));
        assertTrue(error(convert("jsonToXml", "{\"a\":\"avalue\",\"b\":\"bvalue\"}", Json.o())).contains("exactly one"));
        assertTrue(error(convert("jsonToXml", "{}", Json.o())).contains("empty"));
        assertTrue(error(convert("jsonToXml", "{\"a b\":1}", Json.o())).contains("cannot be converted into a valid XML name"));
        assertTrue(error(convert("jsonToXml", "{\"xmlData\":1}", Json.o())).contains("not with 'xml'"));
        assertTrue(error(convert("jsonToXml", "{\"a\":", Json.o())).contains("not valid JSON"));
        // SAP's tip: the root element wraps several members.
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><root><a>avalue</a><b>bvalue</b></root>", body(convert("jsonToXml",
                "{\"a\":\"avalue\",\"b\":\"bvalue\"}", Json.o().with("addRootElement", true).with("rootElementName", "root"))));
        // Escaping and a root namespace from the iFlow's namespace mapping.
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><ns0:root xmlns:ns0=\"urn:x\"><a>&lt;&amp;&gt;</a></ns0:root>",
                body(convert("jsonToXml", "{\"a\":\"<&>\"}", Json.o().with("addRootElement", true).with("rootElementName", "root")
                        .with("rootNamespace", "ns0").with("namespaces", Json.o().with("ns0", "urn:x")))));
    }

    @Test
    void jsonToXmlPrefixes() {
        Json.Obj ns = Json.o().with("useNamespaces", true).with("separator", ".").with("namespaceMapping", "abc = urn:abc");
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><abc:A xmlns:abc=\"urn:abc\"><p.B>x</p.B></abc:A>",
                body(convert("jsonToXml", "{\"abc.A\":{\"p.B\":\"x\"}}", ns)), "SAP: an unmapped prefix keeps the full name");
        Map<String, Object> plain = convert("jsonToXml", "{\"abc.A\":\"v\"}", Json.o());
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><abc.A>v</abc.A>", body(plain));
        assertEquals(List.of(), plain.get("diagnostics"), "a tenant keeps the prefix too (F56), so nothing to note");
    }

    // ---------------------------------------------------------------- XML to CSV

    @Test
    void xmlToCsvWithParentElement() {
        String xml = "<root Category=\"Scanners\"><Type>Source Data</Type><Source>ERP</Source><Products>"
                + "<Product><DimensionWidth>0.3400</DimensionWidth><WeightUnit>KG</WeightUnit><ProductId>HT-1080</ProductId>"
                + "<DimensionUnit>m</DimensionUnit><DimensionHeight>0.0500</DimensionHeight><DimensionDepth>0.4800</DimensionDepth>"
                + "<Weight>2.300</Weight><Name>Photo Scan</Name></Product>"
                + "<Product><DimensionWidth>0.3100</DimensionWidth><WeightUnit>KG</WeightUnit><ProductId>HT-1081</ProductId>"
                + "<DimensionUnit>m</DimensionUnit><DimensionHeight>0.0700</DimensionHeight><DimensionDepth>0.4300</DimensionDepth>"
                + "<Weight>2.400</Weight><Name>Power Scan</Name></Product></Products></root>";
        // SAP's example, with the empty line after the parent's lines and the final line break a tenant writes (F55).
        assertEquals("Category;Type;Source\nScanners;Source Data;ERP\n\n"
                + "DimensionWidth;WeightUnit;ProductId;DimensionUnit;DimensionHeight;DimensionDepth;Weight;Name\n"
                + "0.3400;KG;HT-1080;m;0.0500;0.4800;2.300;Photo Scan\n0.3100;KG;HT-1081;m;0.0700;0.4300;2.400;Power Scan\n",
                body(convert("xmlToCsv", xml, Json.o().with("sourcePath", "root/Products/Product").with("fieldSeparator", ";")
                        .with("csvHeader", "xml").with("includeParent", true).with("parentPath", "root").with("includeAttributes", true))));
        assertEquals("0.3400;KG;HT-1080;m;0.0500;0.4800;2.300;Photo Scan\n0.3100;KG;HT-1081;m;0.0700;0.4300;2.400;Power Scan\n",
                body(convert("xmlToCsv", xml, Json.o().with("sourcePath", "/root/Products/Product").with("fieldSeparator", "semicolon"))));
        assertTrue(error(convert("xmlToCsv", xml, Json.o().with("sourcePath", "/root"))).contains("only simple types"));
        Map<String, Object> attrsOnly = convert("xmlToCsv", "<O><I a=\"1\"/></O>", Json.o().with("sourcePath", "/O/I"));
        assertEquals("\n", body(attrsOnly));
        assertTrue(Json.write(attrsOnly.get("diagnostics")).contains("Include Attribute Values"));
        assertEquals("a\n1\n", body(convert("xmlToCsv", "<O><I a=\"1\"/></O>",
                Json.o().with("sourcePath", "/O/I").with("csvHeader", "xml").with("includeAttributes", true))));
    }

    @Test
    void jsonAndCsvToXmlLikeATenant() throws Exception {
        // probes/converters/ with a tenant's responses (F56): "" is <D></D> and null <E/>; an unmapped prefix stays
        // in the name; a quoted CSV field loses its quotes, an empty line is skipped, an empty value is <Name></Name>.
        String json = Files.readString(Path.of("../../probes/converters/json-to-xml.json"));
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><abc.A><xyz.B xyz.attr1=\"1\" attr2=\"2\">valueB</xyz.B>"
                + "<C>valueC1</C><C>valueC2</C><D></D><E/><F>1.50</F></abc.A>",
                body(convert("jsonToXml", json, Json.o().with("separator", ":"))));
        Path project = Files.createTempDirectory("cpiwb-csv");
        Files.copy(Path.of("../../probes/converters/products.xsd"), project.resolve("products.xsd"));
        String csv = Files.readString(Path.of("../../probes/converters/csv-to-xml.csv"));
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><Products><Product><ProductId>HT-1000</ProductId>"
                + "<Name>Notebook; Basic 15</Name><Category>Notebooks</Category></Product><Product><ProductId>HT-1081"
                + "</ProductId><Name></Name><Category>Scanners</Category></Product><Product><ProductId>HT-1010</ProductId>"
                + "<Name>Notebook Professional 15</Name><Category>Notebooks</Category></Product></Products>",
                body(convert("csvToXml", csv, Json.o().with("schema", "products.xsd").with("targetPath", "Products/Product")
                        .with("fieldSeparator", ";").with("firstLineHeader", true).with("baseDir", project.toString()))));
    }

    @Test
    void xmlToCsvLikeATenant() throws Exception {
        // probes/converters/xml-to-csv.xml with a tenant's settings and response (F55): separator comma,
        // XML based header, parent /root, no attribute values; values are written as they are.
        String xml = Files.readString(Path.of("../../probes/converters/xml-to-csv.xml"));
        assertEquals("Type,Source\nSource Data,ERP\n\nProductId,Name,Note\nHT-1080,Photo Scan,a;b\n"
                + "HT-1081,Power Scan,\"quoted\"\n",
                body(convert("xmlToCsv", xml, Json.o().with("sourcePath", "/root/Products/Product").with("fieldSeparator", ",")
                        .with("csvHeader", "xml").with("includeParent", true).with("parentPath", "/root"))));
        // The same with separator ';' and attribute values (F57): a value with the separator is quoted, inner
        // quotes are not doubled.
        assertEquals("Category;Type;Source\nScanners;Source Data;ERP\n\nProductId;Name;Note\nHT-1080;Photo Scan;\"a;b\"\n"
                + "HT-1081;Power Scan;\"quoted\"\n",
                body(convert("xmlToCsv", xml, Json.o().with("sourcePath", "/root/Products/Product").with("fieldSeparator", ";")
                        .with("csvHeader", "xml").with("includeParent", true).with("parentPath", "/root")
                        .with("includeAttributes", true))));
    }

    // ---------------------------------------------------------------- CSV to XML

    static final String PRODUCTS_XSD = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
            + "<xs:element name=\"Products\"><xs:complexType><xs:sequence>"
            + "<xs:element name=\"Product\" maxOccurs=\"unbounded\" type=\"ProductType\"/></xs:sequence></xs:complexType></xs:element>"
            + "<xs:complexType name=\"ProductType\"><xs:sequence>"
            + "<xs:element name=\"ProductId\" type=\"xs:string\"/><xs:element name=\"Name\" type=\"xs:string\"/>"
            + "<xs:element name=\"Category\" type=\"xs:string\"/><xs:element name=\"DimensionUnit\" type=\"xs:string\"/>"
            + "<xs:element name=\"DimensionDepth\" type=\"xs:string\"/><xs:element name=\"DimensionHeight\" type=\"xs:string\"/>"
            + "<xs:element name=\"DimensionWidth\" type=\"xs:string\"/><xs:element name=\"Weight\" type=\"xs:string\"/>"
            + "<xs:element name=\"WeightUnit\" type=\"xs:string\"/></xs:sequence></xs:complexType></xs:schema>";
    static final String PRODUCTS_CSV = "Category;ProductId;DimensionWidth;WeightUnit;DimensionUnit;DimensionHeight;DimensionDepth;Weight;Name\n"
            + "Notebooks;HT-1000;0.3200;KG;m;0.0300;0.1800;4.200;Notebook Basic 15\n"
            + "Notebooks;HT-1001;0.2900;KG;m;0.0310;0.1700;4.500;Notebook Basic 17\n"
            + "PDAs/Organizers;HT-1007;0.3200;KG;m;0.0300;0.2200;0.200;1UMTS PDA\n"
            + "Notebooks;HT-1010;0.3300;KG;m;0.0300;0.2000;4.300;Notebook Professional 15\n"
            + "Scanners;HT-1081;0.3100;KG;m;0.0700;0.4300;2.400;Power Scan\n";
    static final String PRODUCTS_XML = "<?xml version='1.0' encoding='UTF-8'?><Products>"
            + "<Product><ProductId>HT-1000</ProductId><Name>Notebook Basic 15</Name><Category>Notebooks</Category>"
            + "<DimensionUnit>m</DimensionUnit><DimensionDepth>0.1800</DimensionDepth><DimensionHeight>0.0300</DimensionHeight>"
            + "<DimensionWidth>0.3200</DimensionWidth><Weight>4.200</Weight><WeightUnit>KG</WeightUnit></Product>"
            + "<Product><ProductId>HT-1001</ProductId><Name>Notebook Basic 17</Name><Category>Notebooks</Category>"
            + "<DimensionUnit>m</DimensionUnit><DimensionDepth>0.1700</DimensionDepth><DimensionHeight>0.0310</DimensionHeight>"
            + "<DimensionWidth>0.2900</DimensionWidth><Weight>4.500</Weight><WeightUnit>KG</WeightUnit></Product>"
            + "<Product><ProductId>HT-1010</ProductId><Name>Notebook Professional 15</Name><Category>Notebooks</Category>"
            + "<DimensionUnit>m</DimensionUnit><DimensionDepth>0.2000</DimensionDepth><DimensionHeight>0.0300</DimensionHeight>"
            + "<DimensionWidth>0.3300</DimensionWidth><Weight>4.300</Weight><WeightUnit>KG</WeightUnit></Product></Products>";

    static Json.Obj csvSettings(Path dir, String marker) {
        return Json.o().with("baseDir", dir.toString()).with("schema", "schemas/products.xsd").with("targetPath", "Products/Product")
                .with("recordMarker", marker).with("fieldSeparator", ";").with("firstLineHeader", true).with("headerMapping", "xsd");
    }

    @Test
    void csvToXmlAsInSapsExample(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("schemas"));
        Files.writeString(dir.resolve("schemas/products.xsd"), PRODUCTS_XSD);
        assertEquals(PRODUCTS_XML, body(convert("csvToXml", PRODUCTS_CSV, csvSettings(dir, "Notebooks"))));
        // SAP: a marker column and columns that are not in the XSD (Price) are not mapped; same output.
        String marked = "Marker;Category;ProductId;DimensionWidth;WeightUnit;DimensionUnit;DimensionHeight;DimensionDepth;Weight;Name;Price\n"
                + "Yes;Notebooks;HT-1000;0.3200;KG;m;0.0300;0.1800;4.200;Notebook Basic 15;530.00\n"
                + "Yes;Notebooks;HT-1001;0.2900;KG;m;0.0310;0.1700;4.500;Notebook Basic 17;550.00\n"
                + "No;PDAs/Organizers;HT-1007;0.3200;KG;m;0.0300;0.2200;0.200;1UMTS PDA;130.00\n"
                + "Yes;Notebooks;HT-1010;0.3300;KG;m;0.0300;0.2000;4.300;Notebook Professional 15;599.00\n";
        assertEquals(PRODUCTS_XML, body(convert("csvToXml", marked, csvSettings(dir, "Yes"))));
        // Without a header line the values go to the XSD elements in order; the marker column is not mapped.
        String positional = "Yes;HT-1000;Notebook Basic 15;Notebooks;m;0.1800;0.0300;0.3200;4.200;KG\nNo;x;y\n";
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><Products><Product><ProductId>HT-1000</ProductId><Name>Notebook Basic 15"
                + "</Name><Category>Notebooks</Category><DimensionUnit>m</DimensionUnit><DimensionDepth>0.1800</DimensionDepth>"
                + "<DimensionHeight>0.0300</DimensionHeight><DimensionWidth>0.3200</DimensionWidth><Weight>4.200</Weight>"
                + "<WeightUnit>KG</WeightUnit></Product></Products>",
                body(convert("csvToXml", positional, csvSettings(dir, "Yes").with("firstLineHeader", false))));
    }

    /** XSD rules for the target namespace: global elements (also by ref) are qualified, local ones by form. */
    @Test
    void csvToXmlQualifiesByDeclaration(@TempDir Path dir) throws Exception {
        String xsd = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" xmlns:t=\"urn:t\" targetNamespace=\"urn:t\" "
                + "elementFormDefault=\"unqualified\">"
                + "<xs:element name=\"Root\"><xs:complexType><xs:sequence><xs:element ref=\"t:Row\" maxOccurs=\"unbounded\"/>"
                + "</xs:sequence></xs:complexType></xs:element>"
                + "<xs:element name=\"Row\"><xs:complexType><xs:sequence><xs:element name=\"Value\" type=\"xs:string\"/>"
                + "<xs:element name=\"Code\" form=\"qualified\" type=\"xs:string\"/></xs:sequence></xs:complexType></xs:element>"
                + "</xs:schema>";
        Files.writeString(dir.resolve("t.xsd"), xsd);
        String out = body(convert("csvToXml", "Hello,A\nWorld,B", Json.o().with("baseDir", dir.toString()).with("schema", "t.xsd")
                .with("targetPath", "Root/Row").with("firstLineHeader", false)));
        assertEquals("<?xml version='1.0' encoding='UTF-8'?><ns0:Root xmlns:ns0=\"urn:t\"><ns0:Row><Value>Hello</Value>"
                + "<ns0:Code>A</ns0:Code></ns0:Row><ns0:Row><Value>World</Value><ns0:Code>B</ns0:Code></ns0:Row></ns0:Root>", out);
        // The output is valid against its schema.
        javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(dir.resolve("t.xsd").toFile()).newValidator()
                .validate(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(out)));
    }

    @Test
    void csvToXmlErrorsStayInTheProject(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("p/schemas"));
        Files.writeString(dir.resolve("p/schemas/products.xsd"), PRODUCTS_XSD);
        Files.writeString(dir.resolve("outside.xsd"), PRODUCTS_XSD);
        Path project = dir.resolve("p");
        assertFalse(Json.bool(convert("csvToXml", PRODUCTS_CSV, csvSettings(project, "").with("schema", "../outside.xsd")), "ok", true),
                "a schema outside the project is refused");
        assertTrue(error(convert("csvToXml", PRODUCTS_CSV, csvSettings(project, "").with("targetPath", "Products/Item")))
                .contains("not a child element"));
        assertTrue(error(convert("csvToXml", PRODUCTS_CSV, csvSettings(project, "").with("schema", "pd:P:X:binary")))
                .contains("UnsupportedInSimulator"));
    }

    @Test
    void unknownConversion() {
        assertTrue(error(convert("ediToXml", "", Json.o())).contains("Unknown conversion"));
    }

    @Test
    void reviewEdgeCases() {
        // Strict JSON numbers; the error names the place.
        assertTrue(error(convert("jsonToXml", "{\"a\":01}", Json.o())).contains("must not start with 0"));
        // A character XML does not allow is reported.
        Map<String, Object> ctl = convert("jsonToXml", "{\"a\":\"\\u0001\"}", Json.o());
        assertTrue(Json.write(ctl.get("diagnostics")).contains("U+0001"), () -> Json.write(ctl));
        // A listed array interrupted by another element: the warning does not ask to list it.
        Map<String, Object> run = convert("xmlToJson", "<a><b>1</b><c/><b>2</b></a>", Json.o().with("streaming", true)
                .with("streamingElements", "specific").with("arrayPaths", "/a/b"));
        assertTrue(Json.write(run.get("diagnostics")).contains("appears again after other elements"), () -> Json.write(run));
        // A name with a colon is still checked as a name: no markup gets into the output.
        assertTrue(error(convert("jsonToXml", "{\"a:x><injected/><!--\":\"v\"}", Json.o())).contains("cannot be converted"));
        assertTrue(error(convert("jsonToXml", "{\"a:x><i/>\":\"v\"}", Json.o().with("useNamespaces", true)
                .with("namespaceMapping", "b = urn:b"))).contains("cannot be converted"));
        assertTrue(error(convert("jsonToXml", "{\"a:b:c\":\"v\"}", Json.o())).contains("cannot be converted"));
        // An unknown encoding fails before any output.
        Map<String, Object> enc = convert("xmlToJson", "<a/>", Json.o().with("outputEncoding", "bogus"));
        assertFalse(Json.bool(enc, "ok", true));
        assertNull(enc.get("body"));
    }
}
