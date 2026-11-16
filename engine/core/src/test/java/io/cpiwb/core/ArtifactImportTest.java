package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Value Mapping and package exports (plan F65, F66). The archives are built here with made-up values, in the
 * layout one export of each showed.
 */
@SuppressWarnings("unchecked")
class ArtifactImportTest {

    /** Two agencies; Blue's "PMO" comes from three Red values, one of them marked default (the second group). */
    static final String VM = "<vm version=\"2.0\">"
            + group("g1", "Red", "R1", false, "Blue", "LEAD")
            + group("g2", "Red", "R2", true, "Blue", "PMO")
            + group("g3", "Red", "R3", false, "Blue", "PMO")
            + group("g4", "Red", "R4", false, "Blue", "PMO")
            + "</vm>";

    private static String group(String id, String a1, String v1, boolean d1, String a2, String v2) {
        return "<group id=\"" + id + "\"><entry" + (d1 ? " isDefault=\"true\"" : "") + "><agency>" + a1 + "</agency><schema>Role</schema><value>"
                + v1 + "</value></entry><entry><agency>" + a2 + "</agency><schema>Role</schema><value>" + v2 + "</value></entry></group>";
    }

    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] valueMappingZip(String xml) throws Exception {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("value_mapping.xml", b(xml));
        e.put("META-INF/MANIFEST.MF", b("Manifest-Version: 1.0\nSAP-BundleType: ValueMapping\n"));
        e.put("metainfo.prop", b("source=Red\ntarget=Blue\n"));
        return zip(e);
    }

    /** What getMappedValue returns from the table: the first row that matches. */
    private static String lookup(List<Object> rows, String sa, String sv, String ta) {
        for (Object o : rows) {
            Map<String, Object> r = (Map<String, Object>) o;
            if (sa.equals(r.get("sourceAgency")) && "Role".equals(r.get("sourceIdentifier")) && sv.equals(r.get("sourceValue"))
                    && ta.equals(r.get("targetAgency")) && "Role".equals(r.get("targetIdentifier"))) {
                return (String) r.get("targetValue");
            }
        }
        return null;
    }

    @Test
    void aValueMappingBecomesRowsBothWaysWithTheDefaultFirst(@TempDir Path tmp) throws Exception {
        Path zip = Files.write(tmp.resolve("VM_Red_Blue.zip"), valueMappingZip(VM));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = ArtifactImport.importZip(zip, project, null, true);
        assertEquals("valueMapping", r.get("kind"));
        assertEquals("platform/value-mappings.json", r.get("imported"));
        Map<String, Object> vm = (Map<String, Object>) ((List<Object>) r.get("valueMappings")).get(0);
        assertEquals(4, vm.get("groups"));
        assertEquals(8, vm.get("rows"));
        String text = Files.readString(project.resolve("platform/value-mappings.json"));
        assertFalse(text.contains("\u0000") || text.contains("\\u0000"), text);
        List<Object> rows = (List<Object>) Json.parse(text);
        assertEquals("LEAD", lookup(rows, "Red", "R1", "Blue"));
        assertEquals("PMO", lookup(rows, "Red", "R4", "Blue"));
        assertEquals("R1", lookup(rows, "Blue", "LEAD", "Red"));
        assertEquals("R2", lookup(rows, "Blue", "PMO", "Red"), "the default, though the first group with PMO is g2 anyway");
        assertTrue(((List<Object>) vm.get("notes")).isEmpty(), () -> Json.write(vm));

        // Again: with createNew the pairs that exist are an error; without, they are replaced, other rows stay.
        Files.writeString(project.resolve("platform/value-mappings.json"), text.replaceFirst("\\[\n",
                "[\n  {\"sourceAgency\":\"Shop\",\"sourceIdentifier\":\"C\",\"sourceValue\":\"1\",\"targetAgency\":\"S4\",\"targetIdentifier\":\"C\",\"targetValue\":\"2\"},\n"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArtifactImport.importZip(zip, project, null, true))
                .getMessage().contains("exists already"));
        Map<String, Object> again = ArtifactImport.importZip(zip, project, null, false);
        assertEquals(8, ((Map<String, Object>) ((List<Object>) again.get("valueMappings")).get(0)).get("replaced"));
        List<Object> after = (List<Object>) Json.parse(Files.readString(project.resolve("platform/value-mappings.json")));
        assertEquals(9, after.size());
        assertEquals("Shop", ((Map<String, Object>) after.get(0)).get("sourceAgency"), "rows of other pairs stay");
    }

    @Test
    void aRepeatedValueWithoutADefaultTakesTheFirstGroupAndSaysSo(@TempDir Path tmp) throws Exception {
        String xml = "<vm version=\"2.0\">" + group("a", "Red", "R7", false, "Blue", "X") + group("b", "Red", "R8", false, "Blue", "X") + "</vm>";
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = ArtifactImport.importZip(Files.write(tmp.resolve("vm.zip"), valueMappingZip(xml)), project, null, false);
        Map<String, Object> vm = (Map<String, Object>) ((List<Object>) r.get("valueMappings")).get(0);
        assertTrue(Json.write(vm.get("notes")).contains("1 value(s) map to several values without a default"), () -> Json.write(vm));
        assertEquals("R7", lookup((List<Object>) Json.parse(Files.readString(project.resolve("platform/value-mappings.json"))), "Blue", "X", "Red"));
    }

    @Test
    void aValueMappingWithADoctypeOrAnotherRootIsRefused(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path doctype = Files.write(tmp.resolve("d.zip"), valueMappingZip("<!DOCTYPE vm [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><vm>&x;</vm>"));
        assertThrows(Exception.class, () -> ArtifactImport.importZip(doctype, project, null, false));
        Path other = Files.write(tmp.resolve("o.zip"), valueMappingZip("<other/>"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArtifactImport.importZip(other, project, null, false))
                .getMessage().contains("<vm>"));
        assertFalse(Files.exists(project.resolve("platform/value-mappings.json")));
    }

    @ParameterizedTest
    @CsvSource(value = {"[]|true", "[]|false", "not JSON|true", "not JSON|false",
            "{\"synthetic\":true}|true", "{\"synthetic\":true}|false"}, delimiter = '|')
    void aValueMappingFileLinkOutsideIsRefusedBeforeReadingOrWriting(String original, boolean createNew,
                                                                    @TempDir Path tmp) throws Exception {
        // [] would be imported successfully without the file-level guard; invalid JSON must not even be read.
        Path project = Files.createDirectories(tmp.resolve("project/platform")).getParent();
        Path outside = Files.writeString(tmp.resolve("outside.json"), original);
        Path link = project.resolve(ValueMappingImport.FILE);
        symbolicLink(link, outside);
        Path archive = Files.write(tmp.resolve("VM.zip"), valueMappingZip(VM));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ArtifactImport.importZip(archive, project, null, createNew));
        assertEquals(ValueMappingImport.FILE + " is outside the project (a symbolic link leads outside).", error.getMessage());
        assertTrue(Files.isSymbolicLink(link), "refusing the import must retain the link");
        assertEquals(outside, Files.readSymbolicLink(link));
        assertEquals(original, Files.readString(outside), "the external target must be unchanged");
        assertFalse(Files.exists(project.resolve("iflows")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aValueMappingFileLinkInsideStillImports(boolean createNew, @TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project/platform")).getParent();
        Path target = Files.writeString(project.resolve("table.json"), "[]");
        Path link = project.resolve(ValueMappingImport.FILE);
        symbolicLink(link, target);
        Path archive = Files.write(tmp.resolve("VM.zip"), valueMappingZip(VM));
        ArtifactImport.importZip(archive, project, null, createNew);
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(target, Files.readSymbolicLink(link));
        assertEquals("LEAD", lookup((List<Object>) Json.parse(Files.readString(target)), "Red", "R1", "Blue"));
    }

    private static void symbolicLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here: " + e);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Same, Same, true", "Same, Same, false",
            "Order Flow, Order_Flow, true", "Order Flow, Order_Flow, false",
            "Order:Flow, Order?Flow, true", "Order:Flow, Order?Flow, false",
            "Shared, shared, true", "Shared, shared, false"
    })
    void packageFolderCollisionsAreRefusedBeforeAnyWrites(String first, String second, boolean createNew,
                                                          @TempDir Path tmp) throws Exception {
        assertPackageCollision(first, second, false, createNew, tmp);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void packageFallbackFileNamesAlsoCollide(boolean createNew, @TempDir Path tmp) throws Exception {
        assertPackageCollision("Fallback.zip", "Fallback.ZIP", true, createNew, tmp);
    }

    private static void assertPackageCollision(String first, String second, boolean fallback, boolean createNew,
                                               Path tmp) throws Exception {
        String resources = "{\"resources\":["
                + resource("early", "RESTAPIProvider", "Early.zip", "Early") + ","
                + resource("v1", "ValueMapping", "VM.zip", "VM") + ","
                + resource("one", "IFlow", first, fallback ? "" : first) + ","
                + resource("two", "ScriptCollection", second, fallback ? "" : second) + "]}";
        Map<String, byte[]> pkg = new LinkedHashMap<>();
        pkg.put("resources.cnt", b(Base64.getEncoder().encodeToString(b(resources))));
        pkg.put("early_content", zip(Map.of("early.txt", b("must not be written"))));
        pkg.put("v1_content", valueMappingZip(VM));
        pkg.put("one_content", zip(Map.of("same.txt", b("first artifact"), "first-only.txt", b("first"))));
        pkg.put("two_content", zip(Map.of("same.txt", b("second artifact"), "second-only.txt", b("second"))));
        Path archive = Files.write(tmp.resolve("Collision.zip"), zip(pkg));
        // Both an absent table and existing unrelated rows must remain exactly as they were.
        for (boolean existingTable : List.of(false, true)) {
            Path project = Files.createDirectories(tmp.resolve("project-" + existingTable));
            Files.createDirectories(project.resolve("iflows/Existing"));
            Files.writeString(project.resolve("iflows/Existing/keep.txt"), "existing artifact\n");
            if (existingTable) {
                Files.createDirectories(project.resolve("platform"));
                Files.writeString(project.resolve(ValueMappingImport.FILE),
                        "[ {\"sourceAgency\":\"Other\",\"targetAgency\":\"Elsewhere\",\"sourceValue\":\"keep\"} ]\n");
            }
            Map<String, String> before = snapshot(project);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ArtifactImport.importZip(archive, project, null, createNew));
            assertTrue(error.getMessage().contains("Package artifact folder collision: iflows/"), error::getMessage);
            assertTrue(error.getMessage().contains("one") && error.getMessage().contains("two"), error::getMessage);
            assertEquals(before, snapshot(project), "collision preflight must leave every file and directory unchanged");
        }
    }

    /** Exact synthetic project contents, including directories, to detect even empty partial imports. */
    private static Map<String, String> snapshot(Path project) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.sorted().toList()) {
                result.put(project.relativize(path).toString(), Files.isDirectory(path) ? "directory"
                        : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    /** A package: an iFlow whose script is in a script collection, the collection, a value mapping, a type not imported. */
    @Test
    void aPackageImportsItsArtifactsAndResolvesScriptsOfACollection(@TempDir Path tmp) throws Exception {
        String iflw = "<bpmn2:definitions xmlns:bpmn2=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:ifl=\"http:///com.sap.ifl.model/Ifl.xsd\">"
                + "<bpmn2:process id=\"P\"><bpmn2:callActivity id=\"CallActivity_1\" name=\"Shared script\"><bpmn2:extensionElements>"
                + prop("activityType", "Script") + prop("subActivityType", "GroovyScript") + prop("componentVersion", "1.1")
                + prop("scriptBundleId", "Shared_Scripts") + prop("script", "shared.groovy")
                + "</bpmn2:extensionElements></bpmn2:callActivity>"
                + "<bpmn2:callActivity id=\"CallActivity_2\" name=\"Missing collection\"><bpmn2:extensionElements>"
                + prop("activityType", "Script") + prop("subActivityType", "GroovyScript") + prop("componentVersion", "1.1")
                + prop("scriptBundleId", "Not_Exported") + prop("script", "gone.groovy")
                + "</bpmn2:extensionElements></bpmn2:callActivity></bpmn2:process></bpmn2:definitions>";
        Map<String, byte[]> flow = new LinkedHashMap<>();
        flow.put("src/main/resources/scenarioflows/integrationflow/Order Flow.iflw", b(iflw));
        flow.put("META-INF/MANIFEST.MF", b("Manifest-Version: 1.0\n"));
        // The iFlow's own script of the same name is another script.
        flow.put("src/main/resources/script/shared.groovy", b("def processData(m) { null }"));
        Map<String, byte[]> scripts = new LinkedHashMap<>();
        scripts.put("src/main/resources/script/shared.groovy", b("def processData(m) { m }"));
        String resources = "{\"resources\":["
                + resource("p1", "ContentPackage", "Pkg", "Pkg") + ","
                + resource("i1", "IFlow", "OrderFlow.zip", "OrderFlow") + ","
                + resource("s1", "ScriptCollection", "Shared Scripts.zip", "Shared_Scripts") + ","
                + resource("v1", "ValueMapping", "VM.zip", "VM_Red_Blue") + ","
                + resource("v2", "ValueMapping", "VM2.zip", "VM_Red_Blue_2") + ","
                + resource("d1", "DataType", "Order.zip", "OrderType") + "],\"relations\":[]}";
        Map<String, byte[]> pkg = new LinkedHashMap<>();
        pkg.put("i1_content", zip(flow));
        pkg.put("s1_content", zip(scripts));
        pkg.put("v1_content", valueMappingZip(VM));
        // A second value mapping for the same agencies and identifiers: its rows are added, not put in place of the first's.
        pkg.put("v2_content", valueMappingZip("<vm version=\"2.0\">" + group("h1", "Red", "R9", false, "Blue", "NINE") + "</vm>"));
        pkg.put("d1_content", zip(Map.of("x.xsd", b("<x/>"))));
        pkg.put("resources.cnt", b(Base64.getEncoder().encodeToString(b(resources))));
        pkg.put("contentmetadata.md", b(Base64.getEncoder().encodeToString(b("-- listing properties --\n"))));
        Path zip = Files.write(tmp.resolve("Package.zip"), zip(pkg));
        Path project = Files.createDirectories(tmp.resolve("project"));

        Map<String, Object> r = ArtifactImport.importZip(zip, project, "ignored", true);
        assertEquals("package", r.get("kind"));
        assertEquals(List.of("iflows/OrderFlow", "iflows/Shared_Scripts"), r.get("iflows"));
        assertEquals("iflows/OrderFlow, iflows/Shared_Scripts, platform/value-mappings.json", r.get("imported"));
        assertEquals("DataType", ((Map<String, Object>) ((List<Object>) r.get("skipped")).get(0)).get("type"));
        List<Object> steps = (List<Object>) r.get("steps");
        Map<String, Object> shared = (Map<String, Object>) steps.get(0);
        assertEquals("iflows/Shared_Scripts/src/main/resources/script/shared.groovy", shared.get("file"), () -> Json.write(r));
        assertEquals(true, shared.get("fileFound"));
        Map<String, Object> gone = (Map<String, Object>) steps.get(1);
        assertEquals(false, gone.get("fileFound"));
        assertTrue(gone.get("note").toString().contains("script collection Not_Exported"), () -> Json.write(gone));
        List<Object> rows = (List<Object>) Json.parse(Files.readString(project.resolve("platform/value-mappings.json")));
        assertEquals("LEAD", lookup(rows, "Red", "R1", "Blue"));
        assertEquals("NINE", lookup(rows, "Red", "R9", "Blue"));

        // Again with createNew: the first folder that exists stops it before anything is written.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ArtifactImport.importZip(zip, project, null, true))
                .getMessage().contains("exists already: iflows/OrderFlow"));
        // A package entry that leaves the folder is refused, as in an iFlow.
        pkg.put("i1_content", zip(Map.of("../../evil.txt", b("x"))));
        Path bad = Files.write(tmp.resolve("Bad.zip"), zip(pkg));
        assertThrows(java.io.IOException.class, () -> ArtifactImport.importZip(bad, project, null, false));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    private static String prop(String k, String v) {
        return "<ifl:property><key>" + k + "</key><value>" + v + "</value></ifl:property>";
    }

    private static String resource(String id, String type, String name, String unique) {
        return "{\"id\":\"" + id + "\",\"resourceType\":\"" + type + "\",\"name\":\"" + name + "\",\"uniqueId\":\"" + unique + "\"}";
    }

    @Test
    void anIflowZipStillImportsAsBefore(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> flow = new LinkedHashMap<>();
        flow.put("src/main/resources/scenarioflows/integrationflow/A.iflw", b("<definitions/>"));
        Path project = Files.createDirectories(tmp.resolve("project"));
        Map<String, Object> r = ArtifactImport.importZip(Files.write(tmp.resolve("A.zip"), zip(flow)), project, null, false);
        assertEquals("iflow", r.get("kind"));
        assertEquals("iflows/A", r.get("imported"));
        assertEquals(List.of("iflows/A"), r.get("iflows"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ArtifactImport.importZip(Files.writeString(tmp.resolve("n.zip"), "no zip"), project, null, false))
                .getMessage().contains("Not a zip"));
    }
}
