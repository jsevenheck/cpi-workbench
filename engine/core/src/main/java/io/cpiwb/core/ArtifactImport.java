package io.cpiwb.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports what the tenant exports as a zip, by what the zip holds (docs/plan.md F65, F66):
 * <ul>
 * <li>an iFlow ({@code .iflw} inside): into {@code iflows/<name>/}, as before ({@link IflowImport});</li>
 * <li>a Value Mapping ({@code value_mapping.xml} at the top): rows of {@code platform/value-mappings.json}
 * ({@link ValueMappingImport});</li>
 * <li>an integration package ({@code resources.cnt} at the top): each artifact it lists, the iFlows, REST API
 * providers, script collections and message mappings into {@code iflows/<artifact>/}, the value mappings into
 * the table; other types are listed as skipped.</li>
 * </ul>
 * The export formats are not documented; they are read from one export of each (F65, F66), best effort.
 */
public final class ArtifactImport {

    /** Artifact types of a package that are unpacked as a folder under iflows/. */
    private static final Set<String> FOLDERS = Set.of("IFlow", "RESTAPIProvider", "ScriptCollection", "MessageMapping");

    private ArtifactImport() {
    }

    /** {@code iflow.import}: the result of the import with {@code kind}, the steps of all iFlows and {@code iflows}. */
    public static Map<String, Object> importZip(Path zip, Path projectRoot, String name, boolean createNew) throws Exception {
        return importZip(zip, projectRoot, name, createNew, new ImportBudget());
    }

    static Map<String, Object> importZip(Path zip, Path projectRoot, String name, boolean createNew, ImportBudget budget) throws Exception {
        if (!Files.isRegularFile(zip)) {
            throw new IllegalArgumentException("Not a file: " + zip);
        }
        String kind;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            kind = kind(z);
            if ("valueMapping".equals(kind)) {
                budget.entries(z.size());
                Json.Obj vm = ValueMappingImport.importXml(read(z, z.getEntry("value_mapping.xml"), budget), projectRoot, createNew,
                        zip.getFileName().toString());
                return result(projectRoot, kind, List.of(), List.of(vm), List.of(), ValueMappingImport.FILE, 1, List.of());
            }
            if ("package".equals(kind)) {
                budget.entries(z.size());
                return importPackage(z, projectRoot, createNew, budget);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Not a zip archive: " + zip.getFileName() + " (" + e.getMessage() + ")");
        }
        try (var staging = new IflowImport.Staging()) {
            var artifact = IflowImport.stage(zip, staging.dir, name, budget);
            var x = IflowImport.publish(projectRoot, artifact, createNew);
            return result(projectRoot, "iflow", List.of(x.folder()), List.of(), List.of(), x.folder(), x.files(), x.kept());
        }
    }

    /** iflow, valueMapping or package, by the zip's entries. */
    static String kind(ZipFile z) {
        if (z.getEntry("resources.cnt") != null) {
            return "package";
        }
        if (z.getEntry("value_mapping.xml") != null) {
            return "valueMapping";
        }
        return "iflow";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> importPackage(ZipFile z, Path projectRoot, boolean createNew, ImportBudget budget) throws Exception {
        var wrapped = new ImportBudget(); // the outer zip's manifest and nested ZIP streams also have an aggregate byte limit
        Object parsed = Json.parse(new String(Base64.getMimeDecoder().decode(read(z, z.getEntry("resources.cnt"), wrapped)),
                StandardCharsets.UTF_8));
        if (!(parsed instanceof Map) || !(((Map<String, Object>) parsed).get("resources") instanceof List)) {
            throw new IllegalArgumentException("resources.cnt lists no resources; the package export format may have changed.");
        }
        List<Map<String, Object>> resources = new ArrayList<>();
        for (Object o : (List<Object>) ((Map<String, Object>) parsed).get("resources")) {
            if (o instanceof Map) {
                resources.add((Map<String, Object>) o);
            }
        }
        // The folders first, so that with createNew nothing is written when one exists.
        List<String[]> folders = new ArrayList<>();
        List<Object> skipped = new ArrayList<>();
        List<Map<String, Object>> valueMappings = new ArrayList<>();
        for (Map<String, Object> r : resources) {
            String type = Json.str(r, "resourceType", "");
            String artifact = artifactName(r);
            ZipEntry content = z.getEntry(Json.str(r, "id", "") + "_content");
            if ("ContentPackage".equals(type)) {
                continue; // the package itself
            }
            if (content == null) {
                skipped.add(Json.o().with("name", artifact).with("type", type).with("reason", "no content in the export"));
            } else if ("ValueMapping".equals(type)) {
                valueMappings.add(r);
            } else if (FOLDERS.contains(type)) {
                folders.add(new String[] {Json.str(r, "id", ""), artifact});
            } else {
                skipped.add(Json.o().with("name", artifact).with("type", type).with("reason", "not simulated"));
            }
        }
        // Preflight every folder before writing anything, including the value mapping table. Two package
        // artifacts must never merge into one folder, even in overwrite mode. Compare as Windows does (case,
        // trailing dots) so that the same package is safe there too; the checks are those of IflowImport.extract.
        Map<String, String> destinations = new LinkedHashMap<>();
        for (String[] f : folders) {
            String folder = IflowImport.folderName(f[1]);
            String previous = destinations.putIfAbsent(IflowImport.folderKey(folder), f[0]);
            if (previous != null) {
                throw new IllegalArgumentException("Package artifact folder collision: iflows/" + folder
                        + " (resources " + previous + " and " + f[0] + "). Rename the artifacts in the package before importing.");
            }
            IflowImport.checkExisting(projectRoot, folder);
            if (createNew && Files.exists(projectRoot.resolve("iflows").resolve(folder))) {
                throw new IllegalArgumentException("exists already: iflows/" + folder);
            }
        }
        try (var staging = new IflowImport.Staging()) {
            List<List<ValueMappingImport.Entry>> groups = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Map<String, Object> r : valueMappings) {
                String artifact = artifactName(r);
                var vm = stage(z, Json.str(r, "id", ""), artifact, staging.dir, wrapped, budget);
                Path xml = vm.source().resolve("value_mapping.xml");
                if (!Files.isRegularFile(xml)) {
                    throw new IllegalArgumentException("The value mapping " + artifact + " in the package has no value_mapping.xml.");
                }
                groups.addAll(ValueMappingImport.groups(Files.readAllBytes(xml)));
                names.add(artifact);
            }
            List<IflowImport.Staged> prepared = new ArrayList<>();
            for (String[] f : folders) {
                var artifact = stage(z, f[0], f[1], staging.dir, wrapped, budget);
                IflowImport.checkDestination(projectRoot, artifact, createNew);
                prepared.add(artifact);
            }
            var table = names.isEmpty() ? null : ValueMappingImport.prepare(groups, projectRoot, createNew, String.join(", ", names));

            // Validate all archives before publishing. A filesystem failure during publication can still leave
            // committed folders; report those explicitly and leave the atomic table replacement until last.
            List<String> imported = new ArrayList<>();
            List<Object> vmResults = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            int files = 0;
            try {
                for (var artifact : prepared) {
                    var x = IflowImport.publish(projectRoot, artifact, createNew);
                    imported.add(x.folder());
                    files += x.files();
                    kept.addAll(x.kept());
                }
                if (table != null) vmResults.add(table.publish());
            } catch (IOException | RuntimeException e) {
                if (imported.isEmpty()) throw e;
                throw new IOException("Import stopped after publishing: " + String.join(", ", imported)
                        + ". Review those folders before retrying in overwrite mode. " + e.getMessage(), e);
            }
            List<String> all = new ArrayList<>(imported);
            if (!vmResults.isEmpty()) all.add(ValueMappingImport.FILE);
            return result(projectRoot, "package", imported, vmResults, skipped, String.join(", ", all), files, kept);
        }
    }

    /** The folder name of a package's artifact: its id in the tenant (uniqueId), else its file name. */
    private static String artifactName(Map<String, Object> r) {
        String unique = Json.str(r, "uniqueId", "");
        return unique.isBlank() ? Json.str(r, "name", "artifact").replaceFirst("(?i)\\.zip$", "") : unique;
    }

    private static IflowImport.Staged stage(ZipFile z, String id, String name, Path work,
                                            ImportBudget wrapped, ImportBudget unpacked) throws IOException {
        ZipEntry entry = z.getEntry(id + "_content");
        Path archive = Files.createTempFile(work, "inner-", ".zip");
        try {
            try (var in = checkedStream(z, entry, wrapped); var out = Files.newOutputStream(archive)) {
                in.transferTo(out);
                checkCrc(entry, in);
            }
            return IflowImport.stage(archive, work, name, unpacked);
        } catch (IOException e) {
            throw new IOException("Cannot stage artifact " + name + ": " + e.getMessage(), e);
        } finally {
            IflowImport.deleteQuietly(archive); // best effort; the staging folder's cleanup tries again
        }
    }

    private static CheckedInputStream checkedStream(ZipFile z, ZipEntry entry, ImportBudget budget) throws IOException {
        return new CheckedInputStream(budget.bounded(z.getInputStream(entry)), new CRC32());
    }

    private static void checkCrc(ZipEntry entry, CheckedInputStream in) throws IOException {
        if (entry.getCrc() != in.getChecksum().getValue()) {
            throw new IOException("ZIP checksum mismatch: " + entry.getName());
        }
    }

    private static byte[] read(ZipFile z, ZipEntry e, ImportBudget budget) throws IOException {
        try (var in = checkedStream(z, e, budget)) {
            byte[] bytes = in.readAllBytes();
            checkCrc(e, in);
            return bytes;
        }
    }

    private static Map<String, Object> result(Path projectRoot, String kind, List<String> iflows, List<?> valueMappings,
                                              List<Object> skipped, String imported, int files, List<String> kept) throws IOException {
        Map<String, Object> r = IflowImport.steps(projectRoot);
        r.put("kind", kind);
        r.put("imported", imported);
        r.put("files", files);
        r.put("iflows", new ArrayList<Object>(iflows));
        r.put("valueMappings", new ArrayList<Object>(valueMappings));
        r.put("skipped", skipped);
        // Overwrite imports keep files the export no longer has; they are listed, never deleted (docs/project-files.md).
        r.put("kept", new ArrayList<Object>(kept));
        return r;
    }
}
