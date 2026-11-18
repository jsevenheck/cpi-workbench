package io.cpiwb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File access for the UI, restricted to a selected project folder. The desktop engine keeps an open
 * project; browser calls each get their own instance. Both use the same path checks.
 */
public final class ProjectFiles {

    private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", ".idea", ".vscode", "dist");
    private static final long MAX_READ = 64L * 1024 * 1024;

    private Path root;
    /**
     * Why a scoped request's project folder cannot be used (deleted, renamed, not absolute), or null. Checked when
     * the method needs the project, so that {@code engine.info} and other calls without project files still work.
     * Such a request never falls back to the engine's open project or to no project.
     */
    private String unavailable;

    /** A request-local context: no listing or initialization is needed to use an already selected root. */
    static ProjectFiles scoped(String dir) {
        ProjectFiles files = new ProjectFiles();
        if (dir != null) {
            Path project = Path.of(dir);
            if (!project.isAbsolute() || !Files.isDirectory(project)) {
                files.unavailable = "Project folder is unavailable: " + dir + ". Reopen the project to continue.";
            } else {
                files.root = project.normalize();
            }
        }
        return files;
    }

    public synchronized Map<String, Object> open(Map<String, Object> p) throws IOException {
        Path dir = Path.of(Json.str(p, "dir", "")).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a folder: " + dir);
        }
        if (Json.bool(p, "init", false)) {
            init(dir);
        }
        root = dir;
        unavailable = null;
        Path marker = root.resolve("cpi-workbench.json");
        return Json.o().with("root", root.toString())
                .with("isProject", ProjectPaths.inside(root, marker) && Files.isRegularFile(marker))
                .with("files", list());
    }

    /** Forgets the open project, so runs no longer see its files or platform data. */
    public synchronized Map<String, Object> close() {
        root = null;
        unavailable = null;
        return Json.o().with("closed", true);
    }

    private static void init(Path dir) throws IOException {
        List<String> folders = List.of("mappings", "schemas", "scripts", "platform");
        // Mocked platform data for the Groovy simulator. A keystore is optional.
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("platform/credentials.json", "{\n  \"EXAMPLE_ALIAS\": { \"username\": \"demo-user\", \"password\": \"demo-password\" }\n}\n");
        defaults.put("platform/value-mappings.json", "[\n  { \"sourceAgency\": \"S4\", \"sourceIdentifier\": \"Plant\", \"sourceValue\": \"1000\",\n"
                + "    \"targetAgency\": \"Shop\", \"targetIdentifier\": \"Warehouse\", \"targetValue\": \"WH-A\" }\n]\n");
        defaults.put("platform/partner-directory.json", "{\n  \"partners\": {\n    \"EXAMPLE_PARTNER\": {\n"
                + "      \"parameters\": { \"RECEIVER_URL\": \"https://example.invalid/receiver\" },\n"
                + "      \"alternativePartners\": [ { \"agency\": \"SAP\", \"scheme\": \"GLN\", \"id\": \"4000000000001\" } ]\n"
                + "    }\n  }\n}\n");
        defaults.put("platform/number-ranges.json", "{\n  \"EXAMPLE_RANGE\": { \"next\": 1, \"min\": 1, \"max\": 999999, \"fieldLength\": 6, \"rotate\": false }\n}\n");
        defaults.put("cpi-workbench.json", "{\n  \"name\": " + Json.write(String.valueOf(dir.getFileName())) + "\n}\n");
        // A pre-existing link outside the project should not leave even a partially initialized project.
        for (String folder : folders) {
            ProjectPaths.checked(dir, dir.resolve(folder));
        }
        for (String path : defaults.keySet()) {
            ProjectPaths.checked(dir, dir.resolve(path));
        }
        for (String folder : folders) {
            Files.createDirectories(ProjectPaths.checked(dir, dir.resolve(folder)));
        }
        for (var entry : defaults.entrySet()) {
            Path path = ProjectPaths.checked(dir, dir.resolve(entry.getKey()));
            try {
                Files.writeString(path, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                // Init never replaces an existing file, even if another process created it after the check.
                if (!Files.isRegularFile(path)) {
                    throw new IOException("Not a regular project file: " + entry.getKey(), e);
                }
            }
        }
    }

    public synchronized Map<String, Object> listing() throws IOException {
        requireRoot();
        return Json.o().with("root", root.toString()).with("files", list());
    }

    private List<Object> list() throws IOException {
        return ProjectPaths.scan(root, root, 8, path -> path.equals(root) || !SKIP.contains(path.getFileName().toString()))
                .stream().filter(e -> e.error() == null && !e.path().equals(root)).limit(5000)
                .<Object>map(e -> Json.o().with("path", ProjectPaths.label(root, e.path())).with("dir", e.directory())).toList();
    }

    public synchronized Map<String, Object> read(Map<String, Object> p) throws IOException {
        Path f = resolve(Json.str(p, "path", ""));
        if (Files.size(f) > MAX_READ) {
            throw new IllegalArgumentException("File too large for the editor: " + f);
        }
        if (Json.bool(p, "binary", false)) {
            // A file that is no text (an archive for a Decoder step): its bytes, not an error.
            byte[] bytes = Files.readAllBytes(f);
            return Json.o().with("path", Json.str(p, "path", "")).with("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes))
                    .with("size", bytes.length);
        }
        return Json.o().with("path", Json.str(p, "path", "")).with("content", TextFiles.read(f));
    }

    public synchronized Map<String, Object> write(Map<String, Object> p) throws IOException {
        Path f = resolve(Json.str(p, "path", ""));
        Files.createDirectories(f.getParent());
        // XML that names its encoding is written in it, so the file stays what its declaration says.
        // contentBase64: the bytes of a body that is no text (a step's binary result).
        byte[] content = p.get("contentBase64") != null ? java.util.Base64.getDecoder().decode(Json.str(p, "contentBase64", ""))
                : TextFiles.encode(Json.str(p, "content", ""));
        if (Json.bool(p, "createNew", false)) {
            // Only when no file is there, checked by the file system as it creates it: another process writing
            // the same path at the same time cannot be replaced.
            try {
                Files.write(f, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                // Stable RPC conflict message: the UI and MCP use this to distinguish refusal from other errors.
                throw new IllegalArgumentException("exists already: " + Json.str(p, "path", ""));
            }
        } else {
            Files.write(f, content);
        }
        return Json.o().with("path", Json.str(p, "path", "")).with("saved", true);
    }

    public synchronized Map<String, Object> delete(Map<String, Object> p) throws IOException {
        Path f = resolve(Json.str(p, "path", ""));
        if (Files.isDirectory(f)) {
            try (Stream<Path> s = Files.walk(f)) {
                for (Path x : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                    Files.delete(x);
                }
            }
        } else {
            Files.deleteIfExists(f);
        }
        return Json.o().with("deleted", true);
    }

    /** Imports an exported iFlow, Value Mapping or integration package (ArtifactImport) and lists the steps. */
    public synchronized Map<String, Object> iflowImport(Map<String, Object> p) throws Exception {
        requireRoot();
        Map<String, Object> r = ArtifactImport.importZip(Path.of(Json.str(p, "zip", "")), root, Json.str(p, "name", null),
                Json.bool(p, "createNew", false));
        r.put("fileList", list());
        return r;
    }

    public synchronized Map<String, Object> iflowSteps() throws IOException {
        requireRoot();
        return IflowImport.steps(root);
    }

    /** {@code iflow.models}: processes, elements, flows and layout of the imported iFlows ({@link IflowModel}). */
    public synchronized Map<String, Object> iflowModels() throws IOException {
        requireRoot();
        return IflowModel.models(root);
    }

    /** {@code iflow.index}: the imported iFlows' entries and parameters ({@link IflowIndex}). */
    public synchronized Map<String, Object> iflowIndex() throws IOException {
        requireRoot();
        return IflowIndex.index(root);
    }

    /** {@code iflow.search}: where a value, key or kind is used in the imported iFlows ({@link IflowIndex}). */
    public synchronized Map<String, Object> iflowSearch(Map<String, Object> p) throws IOException {
        requireRoot();
        return IflowIndex.search(root, p);
    }

    /**
     * The project folder, or null when none is open.
     *
     * @throws IllegalArgumentException for a scoped request whose project folder is unavailable
     */
    public synchronized Path root() {
        checkAvailable();
        return root;
    }

    private void checkAvailable() {
        if (unavailable != null) {
            throw new IllegalArgumentException(unavailable);
        }
    }

    private void requireRoot() {
        checkAvailable();
        if (root == null) {
            throw new IllegalStateException("No project folder is open");
        }
    }

    /** Resolves a project-relative path and refuses anything outside the project. */
    public Path resolve(String relative) {
        requireRoot();
        Path f = root.resolve(relative).toAbsolutePath().normalize();
        if (!f.startsWith(root) || f.equals(root)) {
            throw new IllegalArgumentException("Path is outside the project: " + relative);
        }
        if (!ProjectPaths.inside(root, f)) {
            throw new IllegalArgumentException("Path is outside the project: " + relative + " (a symbolic link leads outside)");
        }
        return f;
    }
}
