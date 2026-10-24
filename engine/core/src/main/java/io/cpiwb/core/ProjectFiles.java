package io.cpiwb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File access for the UI, restricted to the currently opened project folder. Used in desktop and
 * web mode alike, so there is exactly one implementation of the sandbox rules.
 */
public final class ProjectFiles {

    private static final Set<String> SKIP = Set.of(".git", "node_modules", "target", ".idea", ".vscode", "dist");
    private static final long MAX_READ = 64L * 1024 * 1024;

    private Path root;

    public synchronized Map<String, Object> open(Map<String, Object> p) throws IOException {
        Path dir = Path.of(Json.str(p, "dir", "")).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a folder: " + dir);
        }
        if (Json.bool(p, "init", false)) {
            init(dir);
        }
        root = dir;
        return Json.o().with("root", root.toString())
                .with("isProject", Files.isRegularFile(root.resolve("cpi-workbench.json")))
                .with("files", list());
    }

    private static void init(Path dir) throws IOException {
        for (String sub : List.of("mappings", "schemas", "scripts", "tests", "platform")) {
            Files.createDirectories(dir.resolve(sub));
        }
        // Mocked platform data for the Groovy simulator (secure store, value mappings).
        Path creds = dir.resolve("platform/credentials.json");
        if (!Files.exists(creds)) {
            Files.writeString(creds, "{\n  \"EXAMPLE_ALIAS\": { \"username\": \"demo-user\", \"password\": \"demo-password\" }\n}\n",
                    StandardCharsets.UTF_8);
        }
        Path vm = dir.resolve("platform/value-mappings.json");
        if (!Files.exists(vm)) {
            Files.writeString(vm, "[\n  { \"sourceAgency\": \"S4\", \"sourceIdentifier\": \"Plant\", \"sourceValue\": \"1000\",\n"
                    + "    \"targetAgency\": \"Shop\", \"targetIdentifier\": \"Warehouse\", \"targetValue\": \"WH-A\" }\n]\n",
                    StandardCharsets.UTF_8);
        }
        Path cfg = dir.resolve("cpi-workbench.json");
        if (!Files.exists(cfg)) {
            Files.writeString(cfg, "{\n  \"name\": \"" + dir.getFileName() + "\",\n"
                    + "  \"defaultXsltProfile\": \"saxon-he-9.9\"\n}\n", StandardCharsets.UTF_8);
        }
    }

    public synchronized Map<String, Object> listing() throws IOException {
        requireRoot();
        return Json.o().with("root", root.toString()).with("files", list());
    }

    private List<Object> list() throws IOException {
        List<Object> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 8)) {
            s.filter(x -> !x.equals(root))
                    .filter(x -> {
                        for (Path part : root.relativize(x)) {
                            if (SKIP.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .sorted()
                    .limit(5000)
                    .forEach(x -> out.add(Json.o()
                            .with("path", root.relativize(x).toString().replace('\\', '/'))
                            .with("dir", Files.isDirectory(x))));
        }
        return out;
    }

    public synchronized Map<String, Object> read(Map<String, Object> p) throws IOException {
        Path f = resolve(Json.str(p, "path", ""));
        if (Files.size(f) > MAX_READ) {
            throw new IllegalArgumentException("File too large for the editor: " + f);
        }
        return Json.o().with("path", Json.str(p, "path", "")).with("content", Files.readString(f, StandardCharsets.UTF_8));
    }

    public synchronized Map<String, Object> write(Map<String, Object> p) throws IOException {
        Path f = resolve(Json.str(p, "path", ""));
        Files.createDirectories(f.getParent());
        Files.writeString(f, Json.str(p, "content", ""), StandardCharsets.UTF_8);
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

    public synchronized Path root() {
        return root;
    }

    private void requireRoot() {
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
        return f;
    }
}
