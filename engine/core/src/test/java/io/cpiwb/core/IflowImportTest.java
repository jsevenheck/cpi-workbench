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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static Path zipWith(Path zip, Map<String, String> entries) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    /** Two entries for one file are refused with both names, before anything is written to the project. */
    @Test
    void duplicateEntriesAreAClearError(@TempDir Path tmp) throws Exception {
        // ZipOutputStream refuses a second entry of one name, so rename the second one in the archive's bytes.
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("dup-1.txt", "first");
        entries.put("dup-2.txt", "second");
        Path zip = zipWith(tmp.resolve("twice.zip"), entries);
        byte[] bytes = Files.readAllBytes(zip);
        String latin = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).replace("dup-2.txt", "dup-1.txt");
        Files.write(zip, latin.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Path project = Files.createDirectories(tmp.resolve("project"));
        var e = assertThrows(java.io.IOException.class, () -> IflowImport.importZip(zip, project, "f", true));
        assertTrue(e.getMessage().contains("Duplicate ZIP entry: dup-1.txt (the archive holds this entry twice)"), e::getMessage);
        assertTrue(Files.notExists(project.resolve("iflows")));
        // Letter case only: one file on a case-insensitive file system, two elsewhere.
        entries.clear();
        entries.put("Script.groovy", "first");
        entries.put("script.groovy", "second");
        Path cased = zipWith(tmp.resolve("cased.zip"), entries);
        Path probe = Files.writeString(tmp.resolve("CaseProbe"), "");
        if (Files.exists(tmp.resolve("caseprobe"))) {
            var c = assertThrows(java.io.IOException.class, () -> IflowImport.importZip(cased, project, "g", true));
            assertTrue(c.getMessage().contains("script.groovy (it collides with the entry Script.groovy"), c::getMessage);
            assertTrue(Files.notExists(project.resolve("iflows/g")));
        } else {
            IflowImport.importZip(cased, project, "g", true);
            assertEquals("second", Files.readString(project.resolve("iflows/g/script.groovy")));
        }
        Files.delete(probe);
    }

    /** A create-new import that fails while writing removes the folder it created, so that a retry can create it. */
    @Test
    void aFailedCreateNewPublishRemovesItsFolder(@TempDir Path tmp) throws Exception {
        Path zip = zipWith(tmp.resolve("ok.zip"), Map.of("a.txt", "x", "sub/b.txt", "y"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        try (var staging = new IflowImport.Staging()) {
            var staged = IflowImport.stage(zip, staging.dir, "f", new ImportBudget());
            Files.delete(staged.source().resolve("sub/b.txt")); // the copy of this one fails
            var e = assertThrows(java.io.IOException.class, () -> IflowImport.publish(project, staged, true));
            assertTrue(e.getMessage().contains("was removed again"), e::getMessage);
            assertTrue(Files.notExists(project.resolve("iflows/f")));
            assertTrue(Files.isDirectory(project.resolve("iflows")), "only the artifact folder is removed");
        }
        IflowImport.importZip(zip, project, "f", true);
        assertEquals("y", Files.readString(project.resolve("iflows/f/sub/b.txt")));
        // In overwrite mode the folder was there before, so it is never removed.
        try (var staging = new IflowImport.Staging()) {
            var staged = IflowImport.stage(zip, staging.dir, "f", new ImportBudget());
            Files.delete(staged.source().resolve("sub/b.txt"));
            var e = assertThrows(java.io.IOException.class, () -> IflowImport.publish(project, staged, false));
            assertTrue(e.getMessage().contains("partially written"), e::getMessage);
            assertEquals("y", Files.readString(project.resolve("iflows/f/sub/b.txt")));
        }
    }

    @Test
    void rollbackLeavesFilesAddedByAnotherWriter(@TempDir Path root) throws Exception {
        Path folder = Files.createDirectory(root.resolve("flow")).toRealPath();
        var recordedFolder = new IflowImport.CreatedEntry(folder);
        Path written = Files.writeString(folder.resolve("imported.txt"), "from the import");
        var recordedFile = new IflowImport.CreatedEntry(written);
        Path userFile = Files.writeString(folder.resolve("notes.txt"), "from the user");

        assertFalse(IflowImport.rollback(root, List.of(recordedFolder, recordedFile)));
        assertTrue(Files.notExists(written));
        assertEquals("from the user", Files.readString(userFile));
    }

    /** Overwriting keeps files the new export no longer has, and lists them in the result. */
    @Test
    void overwriteListsTheFilesItKeeps(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        IflowImport.importZip(zipWith(tmp.resolve("v1.zip"), Map.of("a.txt", "1", "old/Gone.groovy", "1")), project, "f", true);
        Files.writeString(project.resolve("iflows/f/notes.md"), "mine");
        Map<String, Object> r = IflowImport.importZip(zipWith(tmp.resolve("v2.zip"), Map.of("a.txt", "2")), project, "f", false);
        assertEquals(List.of("iflows/f/notes.md", "iflows/f/old/Gone.groovy"), r.get("kept"));
        assertEquals("2", Files.readString(project.resolve("iflows/f/a.txt")));
        assertEquals("mine", Files.readString(project.resolve("iflows/f/notes.md")));
        assertTrue(Files.exists(project.resolve("iflows/f/old/Gone.groovy")));
        Map<String, Object> fresh = ArtifactImport.importZip(zipWith(tmp.resolve("n.zip"), Map.of("a.txt", "3")), project, "n", true);
        assertEquals(List.of(), fresh.get("kept"));
    }

    /** Temporary files another process holds (a virus scanner on Windows) do not fail the import. */
    @Test
    @SuppressWarnings("try") // the open stream is the point: it holds the file
    void stagingCleanupIsBestEffort() throws Exception {
        var staging = new IflowImport.Staging();
        Path sub = Files.createDirectory(staging.dir.resolve("held"));
        Path file = Files.writeString(sub.resolve("file.txt"), "x");
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        try (var held = new java.io.FileInputStream(file.toFile())) { // java.io does not share delete access on Windows
            if (!windows) {
                Files.setPosixFilePermissions(sub, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            }
            staging.close(); // does not throw, whether or not the file could be removed
        } finally {
            if (!windows && Files.exists(sub)) { // root ignores r-x, so close() may have removed it after all
                Files.setPosixFilePermissions(sub, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        }
        assertTrue(IflowImport.deleteTree(staging.dir));
        assertTrue(Files.notExists(staging.dir));
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

        // A choice the export leaves out stays out, so the tab and the MCP server take their default and name it as
        // assumed; an empty string would reach the engine as a scheme or header name instead.
        assertEquals("{}", Json.write(FlowSteps.of(act, Map.of("activityType", "Encoder"), params).get("config")));
        assertEquals("{\"filter\":\"\"}", Json.write(FlowSteps.of(act, Map.of("activityType", "MessageDigest"), params).get("config")));
        assertEquals("{\"sourceXPath\":\"\",\"targetXPath\":\"\"}",
                Json.write(FlowSteps.of(act, Map.of("activityType", "Gather"), params).get("config")));
        assertEquals("{\"expression\":\"\"}", Json.write(FlowSteps.of(act, Map.of("activityType", "Filter"), params).get("config")));
        Map<String, Object> split = Json.obj(FlowSteps.of(act, Map.of("activityType", "Splitter", "splitType", "GeneralSplitter",
                "splitExprValue", "/a"), params), "config");
        assertFalse(split.containsKey("expressionType"));
        assertEquals("/a", split.get("expression"));
    }
}
