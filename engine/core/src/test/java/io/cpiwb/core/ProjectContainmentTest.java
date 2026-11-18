package io.cpiwb.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** POSIX symlinks and Windows directory junctions, without administrator rights. */
class ProjectContainmentTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private final List<Path> links = new ArrayList<>();

    @AfterEach
    void removeLinksBeforeTempDirCleanup() throws IOException {
        Collections.reverse(links);
        for (Path link : links) {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void initRefusesAnExternalPlatformFolderBeforeWritingAnything(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path outside = Files.createDirectory(tmp.resolve("sibling"));
        Files.writeString(outside.resolve("credentials.json"), "unrelated test data");
        directoryLink(project.resolve("platform"), outside);
        ProjectFiles files = new ProjectFiles();
        IOException refused = assertThrows(IOException.class, () -> files.open(open(project, true)));
        assertTrue(refused.getMessage().contains("outside the project: platform"));
        assertFalse(Files.exists(project.resolve("mappings")));
        assertFalse(Files.exists(project.resolve("cpi-workbench.json")));
        assertFalse(Files.exists(outside.resolve("value-mappings.json")));
        assertEquals("unrelated test data", Files.readString(outside.resolve("credentials.json")));
        assertEquals(List.of(), files.open(open(project, false)).get("files"));
    }

    @Test
    void selectedRootCanBeALinkAndInitAcceptsInternalFolders(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("actual"));
        Path platform = Files.createDirectory(project.resolve("local data"));
        Files.writeString(platform.resolve("credentials.json"), "{}");
        directoryLink(project.resolve("platform"), platform);
        Path alias = tmp.resolve("selected root");
        directoryLink(alias, project);
        ProjectFiles files = new ProjectFiles();
        var result = files.open(open(alias, true));
        assertEquals(alias.toString(), result.get("root"));
        assertEquals(true, result.get("isProject"));
        assertEquals("selected root", Json.parseObject(Files.readString(project.resolve("cpi-workbench.json"))).get("name"));
        assertTrue(Files.isRegularFile(platform.resolve("value-mappings.json")));
        assertEquals("{}", Files.readString(platform.resolve("credentials.json")), "Init never replaces an existing default");
        assertEquals("{}", files.read(Map.of("path", "platform/credentials.json")).get("content"));
    }

    @Test
    void initRefusesAnExternalMarkerAndADanglingDefault(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path external = Files.writeString(tmp.resolve("outside.json"), "{}");
        Path marker = project.resolve("cpi-workbench.json");
        fileLink(marker, external);
        ProjectFiles files = new ProjectFiles();
        assertEquals(false, files.open(open(project, false)).get("isProject"));
        IOException refused = assertThrows(IOException.class, () -> files.open(open(project, true)));
        assertTrue(refused.getMessage().contains("outside the project: cpi-workbench.json"));
        assertFalse(Files.exists(project.resolve("platform")));
        assertEquals("{}", Files.readString(external));
        removeLink(marker);
        Path platform = Files.createDirectory(project.resolve("platform"));
        Path target = tmp.resolve("not created.json");
        fileLink(platform.resolve("credentials.json"), target);
        IOException dangling = assertThrows(IOException.class, () -> files.open(open(project, true)));
        assertTrue(dangling.getMessage().contains("platform/credentials.json"));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(platform.resolve("number-ranges.json")));
    }

    @Test
    void stepsDiagramAndSearchNeverTraverseExternalIflowFolders(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path contents = Files.createDirectory(project.resolve("local exports"));
        Path outside = Files.createDirectory(tmp.resolve("other exports"));
        model(outside.resolve("SecretFlow"), "Outside.iflw", "outside-index-marker");
        directoryLink(project.resolve("iflows"), outside);
        assertBlocked(project, "iflows");
        assertTrue(IflowIndex.search(project, Map.of("query", "outside-index-marker")).toString().contains("warnings"));
        removeLink(project.resolve("iflows"));
        directoryLink(project.resolve("iflows"), contents);
        model(contents.resolve("Local"), "Inside.iflw", "inside-index-marker");
        directoryLink(contents.resolve("External"), outside);
        directoryLink(contents.resolve("Local/cycle"), contents);
        assertBlocked(project, "iflows/External");
        var search = IflowIndex.search(project, Map.of("query", "inside-index-marker"));
        assertEquals(1, Json.arr(search, "hits").size(), search.toString());
        assertTrue(search.toString().contains("forms a cycle"));
        var opened = new ProjectFiles().open(open(project, false));
        assertTrue(opened.toString().contains("iflows/Local/Inside.iflw"));
        assertFalse(opened.toString().contains("SecretFlow"));
    }

    @Test
    void modelFilesAndIndividualParameterLinksStayInside(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path flow = project.resolve("iflows/Local");
        Path shared = Files.createDirectory(project.resolve("shared"));
        Path realModel = model(shared, "Real.iflw", "inside-index-marker");
        Files.createDirectories(flow);
        fileLink(flow.resolve("Inside.iflw"), realModel);
        fileLink(flow.resolve("Outside.iflw"), model(outside, "Secret.iflw", "outside-index-marker"));
        assertBlocked(project, "iflows/Local/Outside.iflw");
        assertEquals(1, Json.arr(IflowIndex.search(project, Map.of("query", "inside-index-marker")), "hits").size());
        Path resources = Files.createDirectories(flow.resolve("src/main/resources"));
        Path values = Files.writeString(shared.resolve("parameters.prop"), "LocalFlag=contained-value\n");
        fileLink(resources.resolve("parameters.prop"), values);
        Path definitions = Files.writeString(outside.resolve("parameters.propdef"), """
                <parameters><parameter><name>PrivateDemoFlag</name><description>outside-definition-marker</description></parameter></parameters>
                """);
        fileLink(resources.resolve("parameters.propdef"), definitions);
        var refused = IflowIndex.index(project).toString();
        assertTrue(refused.contains("Cannot read the parameters: Path is outside the project"));
        assertFalse(refused.contains("outside-definition-marker"));
        removeLink(resources.resolve("parameters.propdef"));
        assertTrue(IflowIndex.index(project).toString().contains("contained-value"));
    }

    @Test
    void parameterAndMappingReadsCannotFollowExternalResourceFolders(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path flow = project.resolve("iflows/Local");
        model(flow, "Local.iflw", "inside-index-marker");
        Files.createDirectories(flow.resolve("src/main"));
        Files.writeString(outside.resolve("parameters.prop"), "ExternalDemoFlag=outside-parameter-marker\n");
        Path resources = flow.resolve("src/main/resources");
        directoryLink(resources, outside);
        var steps = IflowImport.steps(project).toString();
        assertTrue(steps.contains("outside the project: iflows/Local/src/main/resources/parameters.prop"), steps);
        var index = IflowIndex.index(project).toString();
        assertTrue(index.contains("Cannot read the parameters: Path is outside the project"), index);
        assertFalse(index.contains("outside-parameter-marker"));

        removeLink(resources);
        Files.createDirectory(resources);
        Files.writeString(outside.resolve("Tool.mmap"), """
                <mapping><lnkRole role="UsedFuncLib"><elem>outsideLib.groovy</elem></lnkRole></mapping>
                """);
        directoryLink(resources.resolve("mmap"), outside);
        Map<String, Object> mapping = Json.obj(Map.of("step", Json.arr(IflowImport.steps(project), "steps").get(0)), "step");
        assertEquals("mmap", mapping.get("type"), mapping.toString());
        assertEquals(false, mapping.get("fileFound"));
        assertEquals(List.of(), mapping.get("scripts"));
        assertFalse(IflowImport.steps(project).toString().contains("outsideLib.groovy"));
    }

    /** An alias folder inside iflows/ (link or junction to another iFlow folder) is read once, under its first name. */
    @Test
    void anInternalAliasFolderYieldsEachModelOnce(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path local = project.resolve("iflows/Local");
        model(local, "Inside.iflw", "alias-index-marker");
        directoryLink(project.resolve("iflows/Alias"), local);
        var models = IflowImport.models(project);
        assertEquals(1, models.size(), models.toString());
        assertEquals(project.resolve("iflows/Alias/Inside.iflw"), models.get(0).path(), "first name in path order");
        assertEquals(1, Json.arr(IflowImport.steps(project), "steps").size());
        assertEquals(1, Json.arr(IflowIndex.search(project, Map.of("query", "alias-index-marker")), "hits").size());
        // The file tree shows both names, as a file manager does; only the readers count a model once.
        var listed = new ProjectFiles().open(open(project, false)).toString();
        assertTrue(listed.contains("iflows/Alias/Inside.iflw") && listed.contains("iflows/Local/Inside.iflw"), listed);
        // Plain entries below a link still resolve to their real path, so containment is unchanged.
        var entries = ProjectPaths.scan(project, project.resolve("iflows"), 4, p -> true);
        for (var e : entries) {
            assertEquals(e.path().toRealPath(), e.resolved(), e.path().toString());
        }
    }

    private static void assertBlocked(Path project, String path) throws Exception {
        for (var response : List.of(IflowImport.steps(project), IflowModel.models(project), IflowIndex.index(project))) {
            String payload = response.toString();
            assertTrue(payload.contains("outside the project: " + path), payload);
            assertFalse(payload.contains("outside-index-marker"), payload);
            assertFalse(payload.contains("SecretFlow"), payload);
        }
        assertEquals(List.of(), IflowIndex.search(project, Map.of("query", "outside-index-marker")).get("hits"));
    }

    private static Map<String, Object> open(Path dir, boolean init) {
        return Map.of("dir", dir.toString(), "init", init);
    }

    private static Path model(Path dir, String name, String value) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="Main">
                    <callActivity id="Mapping" name="Demo Mapping">
                      <extensionElements>
                        <property><key>activityType</key><value>MessageMapping</value></property>
                        <property><key>mappinguri</key><value>dir://mmap/Tool.mmap</value></property>
                        <property><key>flag</key><value>%s</value></property>
                      </extensionElements>
                    </callActivity>
                  </process>
                </definitions>
                """.formatted(value));
    }

    private void removeLink(Path link) throws IOException {
        Files.delete(link);
        links.remove(link);
    }

    private void directoryLink(Path link, Path target) throws Exception {
        if (WINDOWS) {
            Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            byte[] output = command.getInputStream().readAllBytes();
            assertEquals(0, command.waitFor(), () -> new String(output));
        } else {
            Files.createSymbolicLink(link, target);
        }
        links.add(link);
    }

    private void fileLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException e) {
            if (!WINDOWS) throw e;
            abort("File symlinks require Windows Developer Mode or symlink privilege: " + e);
        }
        links.add(link);
    }
}
