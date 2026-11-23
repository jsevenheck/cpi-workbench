package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Imports an exported iFlow (zip) into the project and lists its Groovy Script and XSLT Mapping
 * steps, and its Message Mapping steps with their scripts (the mapping itself is not simulated). The .iflw format is not publicly specified; this reads the BPMN call activities and their
 * key/value properties generically and resolves referenced files by name, so it degrades gracefully
 * when SAP changes property names.
 */
public final class IflowImport {

    private IflowImport() {
    }

    /** Extracts {@code zip} into {@code projectRoot/iflows/<name>/} and returns the steps found. */
    public static Map<String, Object> importZip(Path zip, Path projectRoot, String name) throws IOException {
        return importZip(zip, projectRoot, name, false);
    }

    /**
     * As {@link #importZip(Path, Path, String)}; with {@code createNew} the folder must not exist. The file system
     * checks that as it creates the folder, so of two imports to the same name at once, one fails.
     */
    public static Map<String, Object> importZip(Path zip, Path projectRoot, String name, boolean createNew) throws IOException {
        Extracted x = extract(zip, projectRoot, name, createNew);
        Map<String, Object> r = steps(projectRoot);
        r.put("imported", x.folder());
        r.put("files", x.files());
        r.put("kept", new ArrayList<Object>(x.kept()));
        return r;
    }

    /**
     * A folder under iflows/ (relative to the project), the number of files written into it, and the files that
     * were in that folder before an overwrite import and are not in the export: the import keeps them.
     */
    record Extracted(String folder, int files, List<String> kept) {
    }

    /** Unpacks {@code zip} into {@code iflows/<name>/}, the zip's name without {@code .zip} when none is given. */
    static Extracted extract(Path zip, Path projectRoot, String name, boolean createNew) throws IOException {
        try (var staging = new Staging()) {
            Staged staged = stage(zip, staging.dir, name, new ImportBudget());
            checkDestination(projectRoot, staged, createNew);
            return publish(projectRoot, staged, createNew);
        }
    }

    static final class Staging implements AutoCloseable {
        final Path dir = Files.createTempDirectory("cpiwb-import-");

        Staging() throws IOException { }

        /**
         * Best effort: the import's result does not depend on its temporary files, and a file another process
         * holds (a virus scanner on Windows) must not turn a finished import into a failed one. What cannot be
         * removed is reported on stderr and left to the system's temp folder cleanup.
         */
        @Override public void close() {
            deleteTree(dir);
        }
    }

    /** Deletes {@code dir} and everything below it, continuing past failures; reports them on stderr. Never throws. */
    static boolean deleteTree(Path dir) {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) { // does not follow links
            paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
        } catch (java.nio.file.NoSuchFileException e) {
            return true;
        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("cpiwb: could not list temporary files for removal in " + dir + ": " + e.getMessage());
            return false;
        }
        boolean all = true;
        for (Path p : paths) {
            all &= deleteQuietly(p);
        }
        return all;
    }

    /** Deletes one file or empty folder; a failure is reported on stderr, not thrown. */
    static boolean deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            System.err.println("cpiwb: could not remove " + p + ": " + e);
            return false;
        }
    }

    record Staged(String folder, Path source, List<Path> paths, int files) { }

    /** Unpack and verify the archive without touching any project destination. */
    static Staged stage(Path zip, Path staging, String name, ImportBudget budget) throws IOException {
        String n = name == null || name.isBlank() ? zip.getFileName().toString().replaceFirst("\\.zip$", "") : name;
        String folder = folderName(n);
        int entries;
        try (ZipFile central = new ZipFile(zip.toFile())) {
            entries = central.size();
            budget.entries(entries);
        }
        Path dir = Files.createTempDirectory(staging, "artifact-");
        int files = 0;
        // Each written entry by its normalized name, to name the earlier one when two entries land on one file.
        Map<String, String> written = new java.util.HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (--entries < 0) throw new IOException("ZIP entry count disagrees with its directory.");
                Path out = dir.resolve(e.getName()).normalize();
                if (!out.startsWith(dir) || out.equals(dir) || Path.of(e.getName()).isAbsolute()) {
                    throw new IOException("Zip entry outside the target folder: " + e.getName());
                }
                InputStream content = budget.bounded(in);
                try {
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                        content.transferTo(OutputStream.nullOutputStream());
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(content, out); // stage is empty; duplicate file names are an error
                        files++;
                    }
                } catch (java.nio.file.FileAlreadyExistsException clash) {
                    throw duplicate(e.getName(), written.get(key(dir, out)), clash);
                }
                written.putIfAbsent(key(dir, out), e.getName());
            }
        }
        if (entries != 0) throw new IOException("ZIP entry count disagrees with its directory.");
        try (Stream<Path> paths = Files.walk(dir)) {
            return new Staged(folder, dir, paths.filter(p -> !p.equals(dir)).sorted().toList(), files);
        }
    }

    private static String key(Path dir, Path out) {
        return dir.relativize(out).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /**
     * An export with two entries for one file: the same name twice, names that differ only in letter case on a
     * file system that does not tell them apart, or a file and a folder of one name. Which one CPI would use is
     * not known, so the import refuses the archive rather than picking one.
     */
    private static IOException duplicate(String name, String earlier, IOException cause) {
        String other = earlier == null ? " (it collides with an earlier file or folder of the archive)" : earlier.equals(name) ? " (the archive holds this entry twice)"
                : " (it collides with the entry " + earlier + "; names that differ only in letter case are one file here)";
        return new IOException("Duplicate ZIP entry: " + name + other
                + ". Re-export the artifact, or remove the duplicate from the archive, before importing.", cause);
    }

    static void checkDestination(Path root, Staged staged, boolean createNew) throws IOException {
        checkExisting(root, staged.folder);
        Path target = root.resolve("iflows").resolve(staged.folder);
        Path checked = ProjectPaths.checked(root, target);
        if (createNew && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("exists already: " + ProjectPaths.label(root, target));
        }
        checkType(root, target, checked, true);
        for (Path source : staged.paths) {
            Path destination = target.resolve(staged.source.relativize(source));
            checkType(root, destination, ProjectPaths.checked(root, destination), Files.isDirectory(source));
        }
    }

    private static void checkType(Path root, Path logical, Path checked, boolean directory) throws IOException {
        if (Files.exists(checked) && (directory ? !Files.isDirectory(checked) : !Files.isRegularFile(checked))) {
            throw new IOException("Import destination has the wrong type: " + ProjectPaths.label(root, logical));
        }
    }

    static Extracted publish(Path root, Staged staged, boolean createNew) throws IOException {
        checkDestination(root, staged, createNew);
        Path target = root.resolve("iflows").resolve(staged.folder);
        String label = ProjectPaths.label(root, target);
        List<CreatedEntry> created = new ArrayList<>(); // only paths created by this call
        List<String> kept = createNew ? List.of() : kept(root, target, staged);
        try {
            if (createNew) {
                Files.createDirectories(ProjectPaths.checked(root, target.getParent()));
                Path folder = ProjectPaths.checked(root, target);
                try {
                    Files.createDirectory(folder);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    throw new IllegalArgumentException("exists already: " + label);
                }
                created.add(new CreatedEntry(folder));
            } else {
                Files.createDirectories(ProjectPaths.checked(root, target));
            }
            for (Path source : staged.paths) {
                Path relative = staged.source.relativize(source);
                Path out = ProjectPaths.checked(root, target.resolve(relative));
                if (createNew && !out.equals(created.get(0).path().resolve(relative))) {
                    throw new IOException("Import destination changed while writing: " + ProjectPaths.label(root, target.resolve(relative)));
                }
                if (!createNew) {
                    if (Files.isDirectory(source)) Files.createDirectories(out);
                    else Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING);
                } else if (Files.isDirectory(source)) {
                    Files.createDirectory(out);
                    created.add(new CreatedEntry(out));
                } else {
                    try (InputStream in = Files.newInputStream(source);
                         OutputStream file = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        created.add(new CreatedEntry(out));
                        in.transferTo(file);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            if (!created.isEmpty()) {
                if (rollback(root, created)) {
                    throw new IOException("Could not publish " + label + "; the folder this import created was removed again, "
                            + "so the import can be retried: " + e.getMessage(), e);
                }
                throw new IOException("Could not publish " + label + ", and the folder could not be removed completely. "
                        + "Review its contents before retrying: " + e.getMessage(), e);
            }
            if (createNew) throw e;
            throw new IOException("Could not publish " + label + " (it may be partially written). "
                    + "Review or remove that destination before retrying: " + e.getMessage(), e);
        }
        return new Extracted(label, staged.files, kept);
    }

    record CreatedEntry(Path path, BasicFileAttributes attributes) {
        CreatedEntry(Path path) {
            this(path, attributesOf(path));
        }

        private static BasicFileAttributes attributesOf(Path path) {
            try {
                return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                return null; // do not delete an entry we could not identify
            }
        }

        boolean unchanged() {
            BasicFileAttributes now = attributesOf(path);
            return attributes != null && now != null && !now.isSymbolicLink() && !now.isOther()
                    && now.isDirectory() == attributes.isDirectory()
                    && (attributes.fileKey() != null ? attributes.fileKey().equals(now.fileKey())
                        : now.creationTime().equals(attributes.creationTime()));
        }
    }

    /** Remove only the entries we created, children first. Never delete a directory's untracked contents. */
    static boolean rollback(Path root, List<CreatedEntry> created) {
        boolean complete = true;
        for (int i = created.size() - 1; i >= 0; i--) {
            CreatedEntry entry = created.get(i);
            Path path = entry.path();
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                if (!ProjectPaths.checked(root, path).equals(path) || !entry.unchanged()) {
                    complete = false;
                } else {
                    complete &= deleteQuietly(path);
                }
            } catch (IOException e) {
                complete = false;
            }
        }
        return complete;
    }

    /**
     * Files already in the destination folder that the export does not contain. An overwrite import replaces the
     * export's files and keeps these (they may be the user's own); the result lists them so that the user can
     * remove what the tenant no longer has. Links are not followed out of the project (ProjectPaths.scan).
     */
    static List<String> kept(Path root, Path target, Staged staged) throws IOException {
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        java.util.Set<String> exported = new java.util.HashSet<>();
        for (Path source : staged.paths) {
            exported.add(staged.source.relativize(source).toString().replace('\\', '/'));
        }
        List<String> out = new ArrayList<>();
        for (ProjectPaths.Entry e : ProjectPaths.scan(root, target, 32, path -> true)) {
            if (e.path().equals(target) || e.directory()) {
                continue;
            }
            String relative = target.relativize(e.path()).toString().replace('\\', '/');
            if (!exported.contains(relative)) {
                out.add(ProjectPaths.label(root, e.path()));
            }
        }
        return out;
    }

    /** The folder under {@code iflows/} for an artifact name: characters a folder name should not have become {@code _}. */
    static String folderName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (n.matches("\\.*")) {
            throw new IllegalArgumentException("Invalid iFlow name: '" + n + "'");
        }
        return n;
    }

    /** Folder names Windows treats as one: letter case and trailing dots do not count there. */
    static String folderKey(String folder) {
        return folder.replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a folder that differs from one already in {@code iflows/} only in letter case or trailing dots:
     * on Windows it would be that folder, elsewhere a second one beside it.
     */
    static void checkExisting(Path projectRoot, String folder) throws IOException {
        Path iflows = ProjectPaths.checked(projectRoot, projectRoot.resolve("iflows"));
        if (!Files.isDirectory(iflows)) {
            return;
        }
        try (Stream<Path> existing = Files.list(iflows)) {
            for (Path p : (Iterable<Path>) existing::iterator) {
                String other = p.getFileName().toString();
                if (!other.equals(folder) && folderKey(other).equals(folderKey(folder))) {
                    throw new IllegalArgumentException("iflows/" + folder + " differs from the existing iflows/" + other
                            + " only in letter case or trailing dots, and on Windows they are one folder. Rename one of them before importing.");
                }
            }
        }
    }

    /**
     * Shared discovery for Steps, Diagram and Where used. Refused branches are reported, not scanned; a model
     * reached under two names (a link to another folder of the project) is read once.
     */
    static List<ProjectPaths.Entry> models(Path projectRoot) throws IOException {
        return ProjectPaths.scanUnique(projectRoot, projectRoot.resolve("iflows"), 12, path -> true).stream()
                .filter(e -> e.error() != null || (e.path().toString().endsWith(".iflw") && e.regularFile())).toList();
    }

    /** Scans {@code iflows/} for .iflw files and lists script and XSLT steps. */
    public static Map<String, Object> steps(Path projectRoot) throws IOException {
        return steps(projectRoot, models(projectRoot));
    }

    static Map<String, Object> steps(Path projectRoot, List<ProjectPaths.Entry> models) {
        List<Object> steps = new ArrayList<>();
        Path iflows = projectRoot.resolve("iflows");
        for (ProjectPaths.Entry model : models) {
            try {
                model.requireReadable();
                steps.addAll(parse(model.path(), flowRoot(iflows, model.path()), projectRoot));
            } catch (Exception ex) {
                steps.add(Json.o().with("iflow", ProjectPaths.label(projectRoot, model.path())).with("type", "error")
                        .with("name", "Cannot read iFlow model: " + ex.getMessage()));
            }
        }
        return Json.o().with("steps", steps).with("models", models.size());
    }

    static String iflowName(Path iflows, Path path) {
        return path.equals(iflows) ? "iflows" : iflows.relativize(path).getName(0).toString();
    }

    static Path flowRoot(Path iflows, Path model) {
        return model.equals(iflows) || model.getParent().equals(iflows) ? iflows : iflows.resolve(iflowName(iflows, model));
    }

    private static List<Object> parse(Path model, Path flowRoot, Path projectRoot) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(ProjectPaths.checked(projectRoot, model).toFile());
        List<Object> out = new ArrayList<>();
        String iflow = iflowName(projectRoot.resolve("iflows"), model);
        Properties params = parameters(flowRoot, projectRoot);
        NodeList all = doc.getElementsByTagNameNS("*", "callActivity");
        for (int i = 0; i < all.getLength(); i++) {
            Element act = (Element) all.item(i);
            Map<String, String> props = properties(act);
            Json.Obj flowStep = FlowSteps.of(act, props, params);
            if (flowStep != null) {
                if ("converter".equals(flowStep.get("type"))) {
                    schemaFile(flowStep, flowRoot, projectRoot);
                }
                out.add(flowStep.with("iflow", iflow));
                continue;
            }
            String type = classify(props);
            if (type == null) {
                continue;
            }
            String file = null;
            for (String v : props.values()) {
                if (hasExtension(type, v.toLowerCase(Locale.ROOT))) {
                    file = v;
                    break;
                }
            }
            // A script of a script collection: scriptBundleId names the collection (plan F66). A package import
            // puts the collection into iflows/<its id>/, next to the iFlow. The collection's file, not a file of
            // the same name in the iFlow itself.
            String bundle = props.getOrDefault("scriptBundleId", "").trim();
            String bundleNote = null;
            Path resolved = file == null || !bundle.isEmpty() ? null : resolve(flowRoot, file, projectRoot);
            if (file != null && !bundle.isEmpty()) {
                Path collection = flowRoot.getParent().resolve(bundle.replaceAll("[^A-Za-z0-9._-]+", "_")).normalize();
                if (flowRoot.getParent().equals(collection.getParent()) && ProjectPaths.inside(projectRoot, collection)
                        && Files.isDirectory(collection)) {
                    resolved = resolve(collection, file, projectRoot);
                }
                if (resolved == null) {
                    bundleNote = "The script is in the script collection " + bundle + ", which is not in the project: "
                            + "import the package export, or the collection, too.";
                }
            }
            Json.Obj step = Json.o()
                    .with("iflow", iflow)
                    .with("id", act.getAttribute("id"))
                    .with("name", act.getAttribute("name"))
                    .with("type", type)
                    .with("componentVersion", props.get("componentVersion"))
                    .with("file", resolved == null ? file : projectRoot.relativize(resolved).toString().replace('\\', '/'))
                    .with("fileFound", resolved != null);
            String note = bundleNote != null ? bundleNote : note(type, props, file);
            if (note != null) {
                step.with("note", note);
            }
            if ("mmap".equals(type)) {
                List<String> scripts = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                if (resolved != null) {
                    mappingScripts(resolved, flowRoot, projectRoot, scripts, missing);
                }
                // SAP's patch notes: Groovy Script version 2 is not supported in Message Mappings (plan F41).
                step.with("scripts", scripts).with("profile", "groovy-1.x");
                step.with("note", "Message Mapping is not simulated. " + (resolved == null ? "Its .mmap is not in the export."
                        : scripts.isEmpty() && missing.isEmpty() ? "It uses no Groovy scripts."
                        : "Its script functions run in the Groovy tab.")
                        + (missing.isEmpty() ? "" : " Not found in the export: " + String.join(", ", missing) + "."));
            }
            if ("groovy".equals(type)) {
                String fn = props.get("scriptFunction");
                step.with("function", fn == null || fn.isBlank() ? "processData" : fn.trim());
                step.with("profile", majorVersion(props.get("componentVersion")) >= 2 ? "groovy-2.x" : "groovy-1.x");
            }
            out.add(step);
        }
        NodeList gateways = doc.getElementsByTagNameNS("*", "exclusiveGateway");
        for (int i = 0; i < gateways.getLength(); i++) {
            Element gw = (Element) gateways.item(i);
            out.add(FlowSteps.router(doc, gw, properties(gw), params).with("iflow", iflow));
        }
        return out;
    }

    /** A CSV to XML converter's schema (/xsd/x.xsd in the iFlow's resources) as a project path. */
    @SuppressWarnings("unchecked")
    private static void schemaFile(Json.Obj step, Path flowRoot, Path projectRoot) throws IOException {
        Map<String, Object> config = (Map<String, Object>) step.get("config");
        String schema = Json.str(config, "schema", "");
        if (schema.isBlank() || schema.contains("${") || schema.startsWith("pd:")) {
            return; // set at runtime: the Flow Steps tab resolves ${...}; the Partner Directory is not simulated
        }
        Path p = resolve(flowRoot, schema, projectRoot);
        if (p != null) {
            config.put("schema", projectRoot.relativize(p).toString().replace('\\', '/'));
        } else {
            step.with("note", "The schema " + schema + " is not in the export.");
        }
    }

    /** The iFlow's externalized parameters (src/main/resources/parameters.prop), if it has them. */
    private static Properties parameters(Path flowRoot, Path projectRoot) throws IOException {
        Properties p = new Properties();
        Path file = ProjectPaths.checked(projectRoot, flowRoot.resolve("src/main/resources/parameters.prop"));
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
        }
        return p;
    }

    /** ifl:property children: {@code <key>..</key><value>..</value>}. */
    static Map<String, String> properties(Element act) {
        Map<String, String> props = new LinkedHashMap<>();
        NodeList ps = act.getElementsByTagNameNS("*", "property");
        for (int j = 0; j < ps.getLength(); j++) {
            String key = null;
            String value = null;
            for (Node c = ps.item(j).getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String ln = c.getLocalName() == null ? c.getNodeName() : c.getLocalName();
                if ("key".equals(ln)) {
                    key = c.getTextContent().trim();
                } else if ("value".equals(ln)) {
                    value = c.getTextContent().trim();
                }
            }
            if (key != null) {
                props.put(key, value == null ? "" : value);
            }
        }
        return props;
    }

    /**
     * Step type from the activity properties (as seen in SAP's public sample iFlows:
     * {@code activityType} Script/Mapping/XmlValidator, {@code subActivityType} GroovyScript/XSLTMapping,
     * {@code cmdVariantUri} ...cname::GroovyScript...), else from referenced file extensions.
     */
    private static String classify(Map<String, String> props) {
        String all = (props.getOrDefault("activityType", "") + " " + props.getOrDefault("subActivityType", "")
                + " " + props.getOrDefault("cmdVariantUri", "")).toLowerCase(Locale.ROOT);
        if (all.contains("javascript")) {
            return null;
        }
        // A Message Mapping step references its .mmap (mappinguri dir://mmap/...; SAP's sample iFlows).
        if (all.contains("messagemapping")
                || props.values().stream().anyMatch(v -> hasExtension("mmap", v.toLowerCase(Locale.ROOT)))) {
            return "mmap";
        }
        if (all.contains("groovy") || all.contains("script")) {
            return "groovy";
        }
        if (all.contains("xslt")) {
            return "xslt";
        }
        if (all.contains("xmlvalidator")) {
            return "xsd";
        }
        for (String type : List.of("groovy", "xslt")) {
            for (String v : props.values()) {
                if (hasExtension(type, v.toLowerCase(Locale.ROOT))) {
                    return type;
                }
            }
        }
        return null;
    }

    private static boolean hasExtension(String type, String lowerValue) {
        switch (type) {
            case "groovy":
                return lowerValue.endsWith(".groovy") || lowerValue.endsWith(".gsh");
            case "xslt":
                return lowerValue.endsWith(".xsl") || lowerValue.endsWith(".xslt");
            case "xsd":
                return lowerValue.endsWith(".xsd");
            case "mmap":
                return lowerValue.endsWith(".mmap");
            default:
                return false;
        }
    }

    /**
     * The Groovy scripts a Message Mapping uses: in the .mmap, each {@code lnkRole role="UsedFuncLib"}
     * holds a key whose first two {@code elem} values are the file name and its folder (SAP's sample
     * iFlow "Accessing Header Property and Externalized Parameter from Message Mapping").
     */
    private static void mappingScripts(Path mmap, Path flowRoot, Path projectRoot, List<String> found,
                                       List<String> missing) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(ProjectPaths.checked(projectRoot, mmap).toFile());
            NodeList roles = doc.getElementsByTagNameNS("*", "lnkRole");
            for (int i = 0; i < roles.getLength(); i++) {
                Element role = (Element) roles.item(i);
                if (!"UsedFuncLib".equals(role.getAttribute("role"))) {
                    continue;
                }
                NodeList elems = role.getElementsByTagNameNS("*", "elem");
                if (elems.getLength() == 0) {
                    continue;
                }
                String name = elems.item(0).getTextContent().trim();
                String dir = elems.getLength() > 1 ? elems.item(1).getTextContent().trim() : "";
                Path p = resolve(flowRoot, dir.isEmpty() ? name : dir + "/" + name, projectRoot);
                String rel = p == null ? null : projectRoot.relativize(p).toString().replace('\\', '/');
                if (rel == null) {
                    missing.add(name);
                } else if (!found.contains(rel)) {
                    found.add(rel);
                }
            }
        } catch (Exception e) {
            missing.add("(cannot read " + mmap.getFileName() + ": " + e.getMessage() + ")");
        }
    }

    /** Explains a step whose file cannot be opened directly. */
    private static String note(String type, Map<String, String> props, String file) {
        String source = props.getOrDefault("mappingSource", "");
        if ("xslt".equals(type) && "mappingSrcHeader".equals(source)) {
            return "The mapping is chosen at runtime from the header "
                    + props.getOrDefault("mappingHeaderNameKey", "(unknown)") + "; open the stylesheet yourself.";
        }
        if (file == null) {
            return "xsd".equals(type) ? "No schema file referenced in the step."
                    : "No file referenced in the step (not configured, or from another artifact).";
        }
        return null;
    }

    /** Resolves a referenced file: as a path inside the flow, else by file name below src/main/resources. */
    private static Path resolve(Path flowRoot, String ref, Path projectRoot) throws IOException {
        String clean = ref.replaceFirst("^[a-z]+://", "").replace('\\', '/').replaceFirst("^/+", "");
        for (Path base : List.of(flowRoot, flowRoot.resolve("src/main/resources"))) {
            Path p = base.resolve(clean).normalize();
            if (p.startsWith(flowRoot) && ProjectPaths.inside(projectRoot, p) && Files.isRegularFile(p)) {
                return p;
            }
        }
        String fileName = clean.substring(clean.lastIndexOf('/') + 1);
        return ProjectPaths.scan(projectRoot, flowRoot, 12, path -> true).stream()
                .filter(e -> e.path().getFileName().toString().equals(fileName) && e.regularFile())
                .map(ProjectPaths.Entry::path).findFirst().orElse(null);
    }

    private static int majorVersion(String v) {
        if (v == null || v.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(v.trim().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
