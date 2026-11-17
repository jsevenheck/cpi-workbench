package io.cpiwb.core;

import java.io.IOException;
import java.io.InputStream;
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
    private static final long MAX_PACKAGE = 200L * 1024 * 1024;

    private ArtifactImport() {
    }

    /** {@code iflow.import}: the result of the import with {@code kind}, the steps of all iFlows and {@code iflows}. */
    public static Map<String, Object> importZip(Path zip, Path projectRoot, String name, boolean createNew) throws Exception {
        if (!Files.isRegularFile(zip)) {
            throw new IllegalArgumentException("Not a file: " + zip);
        }
        String kind;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            kind = kind(z);
            if ("valueMapping".equals(kind)) {
                Json.Obj vm = ValueMappingImport.importXml(read(z, z.getEntry("value_mapping.xml"), MAX_PACKAGE), projectRoot, createNew,
                        zip.getFileName().toString());
                return result(projectRoot, kind, List.of(), List.of(vm), List.of(), ValueMappingImport.FILE, 1);
            }
            if ("package".equals(kind)) {
                return importPackage(z, projectRoot, createNew);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Not a zip archive: " + zip.getFileName() + " (" + e.getMessage() + ")");
        }
        IflowImport.Extracted x = IflowImport.extract(zip, projectRoot, name, createNew);
        return result(projectRoot, "iflow", List.of(x.folder()), List.of(), List.of(), x.folder(), x.files());
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
    private static Map<String, Object> importPackage(ZipFile z, Path projectRoot, boolean createNew) throws Exception {
        Object parsed = Json.parse(new String(Base64.getMimeDecoder().decode(read(z, z.getEntry("resources.cnt"), MAX_PACKAGE)),
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
        long budget = MAX_PACKAGE;
        // The package's value mappings as one table: nothing is written when one of them exists (createNew), and
        // none replaces the rows of another for the same agencies and identifiers.
        List<List<ValueMappingImport.Entry>> groups = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : valueMappings) {
            byte[] inner = read(z, z.getEntry(Json.str(r, "id", "") + "_content"), budget);
            budget -= inner.length;
            groups.addAll(ValueMappingImport.groups(valueMappingXml(inner, artifactName(r))));
            names.add(artifactName(r));
        }
        List<Object> vmResults = new ArrayList<>();
        if (!names.isEmpty()) {
            vmResults.add(ValueMappingImport.importGroups(groups, projectRoot, createNew, String.join(", ", names)));
        }
        List<String> imported = new ArrayList<>();
        int files = 0;
        for (String[] f : folders) {
            byte[] inner = read(z, z.getEntry(f[0] + "_content"), budget);
            budget -= inner.length;
            Path tmp = Files.createTempFile("cpiwb-artifact-", ".zip");
            try {
                Files.write(tmp, inner);
                IflowImport.Extracted x = IflowImport.extract(tmp, projectRoot, f[1], createNew);
                imported.add(x.folder());
                files += x.files();
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        List<String> all = new ArrayList<>(imported);
        if (!vmResults.isEmpty()) {
            all.add(ValueMappingImport.FILE);
        }
        return result(projectRoot, "package", imported, vmResults, skipped, String.join(", ", all), files);
    }

    /** The folder name of a package's artifact: its id in the tenant (uniqueId), else its file name. */
    private static String artifactName(Map<String, Object> r) {
        String unique = Json.str(r, "uniqueId", "");
        return unique.isBlank() ? Json.str(r, "name", "artifact").replaceFirst("(?i)\\.zip$", "") : unique;
    }

    /** The value_mapping.xml inside a package's Value Mapping artifact (itself a zip). */
    private static byte[] valueMappingXml(byte[] artifact, String name) throws IOException {
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(artifact))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if ("value_mapping.xml".equals(e.getName())) {
                    return in.readNBytes((int) MAX_PACKAGE);
                }
            }
        }
        throw new IllegalArgumentException("The value mapping " + name + " in the package has no value_mapping.xml.");
    }

    private static byte[] read(ZipFile z, ZipEntry e, long budget) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            byte[] b = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, budget + 1));
            if (b.length > budget) {
                throw new IOException("The export is larger than " + (MAX_PACKAGE >> 20) + " MB unpacked");
            }
            return b;
        }
    }

    private static Map<String, Object> result(Path projectRoot, String kind, List<String> iflows, List<?> valueMappings,
                                              List<Object> skipped, String imported, int files) throws IOException {
        Map<String, Object> r = IflowImport.steps(projectRoot);
        r.put("kind", kind);
        r.put("imported", imported);
        r.put("files", files);
        r.put("iflows", new ArrayList<Object>(iflows));
        r.put("valueMappings", new ArrayList<Object>(valueMappings));
        r.put("skipped", skipped);
        return r;
    }
}
