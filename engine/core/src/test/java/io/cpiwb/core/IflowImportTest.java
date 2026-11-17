package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class IflowImportTest {

    private static Path zipOf(Path dir, Path zip) throws Exception {
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os);
             Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                z.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, z);
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void importsZipAndListsSteps(@TempDir Path tmp) throws Exception {
        Path fixture = Path.of(getClass().getResource("/iflow").toURI());
        Path zip = zipOf(fixture, tmp.resolve("Order_Replication.zip"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = IflowImport.importZip(zip, project, null);
        assertEquals("iflows/Order_Replication", r.get("imported"));
        List<Object> steps = (List<Object>) r.get("steps");
        assertEquals(13, steps.size(), () -> Json.write(r));
        Map<String, Object> g = (Map<String, Object>) steps.get(0);
        assertEquals("groovy", g.get("type"));
        assertEquals("enrich", g.get("function"));
        assertEquals("groovy-2.x", g.get("profile"));
        assertEquals("iflows/Order_Replication/src/main/resources/script/enrich.groovy", g.get("file"));
        Map<String, Object> x = (Map<String, Object>) steps.get(1);
        assertEquals("xslt", x.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/mapping/order.xsl", x.get("file"));
        assertTrue((Boolean) x.get("fileFound"));

        Map<String, Object> blankFn = (Map<String, Object>) steps.get(2);
        assertEquals("processData", blankFn.get("function"));
        assertEquals("groovy-1.x", blankFn.get("profile"));
        Map<String, Object> dynamic = (Map<String, Object>) steps.get(3);
        assertEquals("xslt", dynamic.get("type"));
        assertEquals(false, dynamic.get("fileFound"));
        assertTrue(dynamic.get("note").toString().contains("xsltmappingname"));
        Map<String, Object> xsd = (Map<String, Object>) steps.get(4);
        assertEquals("xsd", xsd.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/xsd/order.xsd", xsd.get("file"));

        // Content Modifier, Splitter, Gather, Filter and Router, with {{parameters}} from parameters.prop.
        Map<String, Object> cm = (Map<String, Object>) steps.get(5);
        assertEquals("contentModifier", cm.get("type"));
        Map<String, Object> cmConfig = Json.obj(cm, "config");
        assertEquals("expression", cmConfig.get("bodyType"));
        assertEquals("<Wrapped>${in.body}</Wrapped>", cmConfig.get("body"));
        Map<String, Object> h1 = (Map<String, Object>) Json.arr(cmConfig, "headers").get(0);
        assertEquals("xpath", h1.get("type"));
        assertEquals("//Order/@id", h1.get("value"));
        assertEquals("java.lang.String", h1.get("datatype"));
        assertEquals("$include=X-*", ((Map<String, Object>) Json.arr(cmConfig, "headers").get(1)).get("name"));
        assertEquals("INFO", ((Map<String, Object>) Json.arr(cmConfig, "properties").get(0)).get("value"));
        Map<String, Object> split = Json.obj((Map<String, Object>) steps.get(6), "config");
        assertEquals("general", split.get("splitter"));
        assertEquals("/Order/Item/", split.get("expression"));
        assertEquals("2", split.get("grouping"));
        Map<String, Object> gather = (Map<String, Object>) steps.get(7);
        assertEquals("gather", gather.get("type"));
        assertEquals("combineAtXPath", Json.obj(gather, "config").get("algorithm"));
        assertEquals("sameXml", Json.obj(gather, "config").get("messageType"));
        assertEquals("/Order/Item", Json.obj(gather, "config").get("sourceXPath"));
        Map<String, Object> filter = (Map<String, Object>) steps.get(8);
        assertEquals("filter", filter.get("type"));
        assertEquals("nodelist", Json.obj(filter, "config").get("valueType"));
        assertEquals("/Order/Item", Json.obj(filter, "config").get("expression"));
        Map<String, Object> mmap = (Map<String, Object>) steps.get(9);
        assertEquals("mmap", mmap.get("type"));
        assertEquals("iflows/Order_Replication/src/main/resources/mapping/order.mmap", mmap.get("file"));
        assertEquals(List.of("iflows/Order_Replication/src/main/resources/script/lookup.gsh"), mmap.get("scripts"));
        assertEquals("groovy-1.x", mmap.get("profile"));
        assertTrue(mmap.get("note").toString().contains("not simulated") && mmap.get("note").toString().contains("gone.gsh"),
                () -> String.valueOf(mmap.get("note")));
        Map<String, Object> j2x = (Map<String, Object>) steps.get(10);
        assertEquals("converter", j2x.get("type"));
        Map<String, Object> j2xConfig = Json.obj(j2x, "config");
        assertEquals("jsonToXml", j2xConfig.get("conversion"));
        assertEquals(true, j2xConfig.get("useNamespaces"));
        assertEquals("Orders", j2xConfig.get("rootElementName"), "{{parameters}} are filled in");
        assertEquals(List.of(Map.of("prefix", "ord", "namespace", "urn:orders")), j2xConfig.get("namespaceMapping"));
        Map<String, Object> c2x = Json.obj((Map<String, Object>) steps.get(11), "config");
        assertEquals("csvToXml", c2x.get("conversion"));
        assertEquals("iflows/Order_Replication/src/main/resources/xsd/order.xsd", c2x.get("schema"));
        assertEquals("Order/Item", c2x.get("targetPath"));
        assertEquals(true, c2x.get("firstLineHeader"));
        assertEquals("xsd", c2x.get("headerMapping"));
        Map<String, Object> router = (Map<String, Object>) steps.get(12);
        assertEquals("router", router.get("type"));
        List<Object> routes = Json.arr(Json.obj(router, "config"), "routes");
        assertEquals("ERP", ((Map<String, Object>) routes.get(0)).get("name"));
        assertEquals("nonxml", ((Map<String, Object>) routes.get(0)).get("expressionType"));
        assertEquals("${header.SenderId} = 'ERP'", ((Map<String, Object>) routes.get(0)).get("condition"));
        assertEquals(true, ((Map<String, Object>) routes.get(1)).get("default"));
    }

    @Test
    void contentModifierBodyType() throws Exception {
        org.w3c.dom.Element act = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                .newDocument().createElement("callActivity");
        java.util.Properties params = new java.util.Properties();
        params.setProperty("type", "Constant");
        // Versions up to 1.3 have no bodyType; their body is an expression (SAP's sample iFlows rely on it).
        Map<String, Object> old = FlowSteps.of(act, Map.of("activityType", "Enricher", "componentVersion", "1.2",
                "wrapContent", "Header = ${header.x}"), params);
        assertEquals("expression", Json.obj(old, "config").get("bodyType"));
        Map<String, Object> param = FlowSteps.of(act, Map.of("activityType", "Enricher", "bodyType", "{{type}}"), params);
        assertEquals("constant", Json.obj(param, "config").get("bodyType"));
    }

    @Test
    void rejectsZipSlip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../../outside.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        assertThrows(java.io.IOException.class, () -> IflowImport.importZip(zip, project, "evil"));
        assertTrue(Files.notExists(tmp.resolve("outside.txt")));
    }

    @Test
    void rejectsNamesThatLeaveTheIflowsFolder(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("ok.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        for (String name : List.of("..", ".")) {
            assertThrows(IllegalArgumentException.class, () -> IflowImport.importZip(zip, project, name), name);
        }
        assertTrue(Files.notExists(project.resolve("a.txt")));
        assertTrue(Files.notExists(project.resolve("iflows/a.txt")));
    }

    /** A folder Windows would take for an existing one (case, trailing dots) is refused, also when overwriting. */
    @Test
    void refusesAFolderThatDiffersFromAnExistingOneOnlyAsWindowsIgnores(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("ok.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project/iflows/Shared")).getParent().getParent();
        for (String name : List.of("shared", "Shared.", "SHARED..")) {
            for (boolean createNew : List.of(true, false)) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> IflowImport.extract(zip, project, name, createNew), name);
                assertTrue(e.getMessage().contains("existing iflows/Shared"), e::getMessage);
            }
        }
        try (var listed = Files.list(project.resolve("iflows"))) {
            assertEquals(List.of("Shared"), listed.map(p -> p.getFileName().toString()).toList());
        }
        assertTrue(Files.notExists(project.resolve("iflows/Shared/a.txt")));
        IflowImport.extract(zip, project, "Shared", false); // the same folder: overwriting stays allowed
        assertTrue(Files.exists(project.resolve("iflows/Shared/a.txt")));
    }

    /** createNew: the file system decides whether the folder is new, so two imports at once cannot both write it. */
    @Test
    void createNewRefusesAnExistingFolder(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("ok.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        Path project = Files.createDirectories(tmp.resolve("project"));
        IflowImport.importZip(zip, project, "f", true);
        Files.writeString(project.resolve("iflows/f/a.txt"), "mine");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> IflowImport.importZip(zip, project, "f", true));
        assertTrue(e.getMessage().contains("exists already"), e.getMessage());
        assertEquals("mine", Files.readString(project.resolve("iflows/f/a.txt")));
        IflowImport.importZip(zip, project, "f", false);
        assertEquals("x", Files.readString(project.resolve("iflows/f/a.txt")));
    }

    /** Encoder and Message Digest, with the keys of the public iFlows of plan F62. */
    @Test
    void encoderAndMessageDigestStepsAreRead() throws Exception {
        org.w3c.dom.Element act = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().newDocument().createElement("callActivity");
        act.setAttribute("id", "CallActivity_1");
        act.setAttribute("name", "Step");
        java.util.Properties params = new java.util.Properties();
        params.setProperty("Header", "MyDigest");
        Map<String, Object> zip = FlowSteps.of(act, Map.of("activityType", "Encoder", "encoderType", "ZIP Compress"), params);
        assertEquals("encoder", zip.get("type"));
        assertEquals("zipCompress", Json.obj(zip, "config").get("scheme"));
        assertEquals("base64Encode", Json.obj(FlowSteps.of(act, Map.of("activityType", "Encoder", "encoderType", "Base64 Encode"), params), "config").get("scheme"));
        // A Decoder keeps its scheme in encoderType as well.
        Map<String, Object> unzip = FlowSteps.of(act, Map.of("activityType", "Decoder", "encoderType", "ZIP Decompress"), params);
        assertEquals("decoder", unzip.get("type"));
        assertEquals("zipDecompress", Json.obj(unzip, "config").get("scheme"));

        Map<String, String> seen = Map.of("activityType", "MessageDigest", "componentVersion", "1.1", "canonicalizationMethod", "none",
                "digestAlgorithm", "SHA-512", "targetHeader", "{{Header}}", "cmdVariantUri", "ctype::FlowstepVariant/cname::MessageDigest/version::1.1.1");
        Map<String, Object> digest = FlowSteps.of(act, seen, params);
        assertEquals("messageDigest", digest.get("type"));
        assertEquals("{\"filter\":\"\",\"canonicalization\":\"none\",\"algorithm\":\"SHA-512\",\"targetHeader\":\"MyDigest\"}", Json.write(digest.get("config")));
        assertNull(digest.get("note"));
        // A key the one sample did not have may be the filter: the step says what it did not read.
        Map<String, String> more = new java.util.TreeMap<>(seen);
        more.put("xpathFilter", "/a/b");
        more.put("empty", " ");
        String note = (String) FlowSteps.of(act, more, params).get("note");
        assertTrue(note.contains("does not read: xpathFilter.") && note.contains("F62"), note);
    }
}
