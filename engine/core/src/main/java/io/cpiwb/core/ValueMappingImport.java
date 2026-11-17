package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An exported Value Mapping artifact (docs/plan.md F65) as rows of {@code platform/value-mappings.json}, the
 * table {@code ValueMappingApi.getMappedValue} reads.
 * <p>
 * The artifact's {@code value_mapping.xml} holds groups of equivalent values, one {@code entry} (agency, schema,
 * value) per side. SAP calls a value mapping a bi-directional lookup table, and when target values repeat for
 * different source values, one of them is the default (S52). So each group becomes one row per direction, and
 * rows whose target is an entry marked {@code isDefault="true"} come first: the table returns the first row
 * that matches, so a lookup from a repeated value gives the default. That the API answers such a lookup with
 * the default is a reading of SAP's documentation, not observed on a tenant; without a default the first group
 * in the file wins, which is an assumption.
 */
public final class ValueMappingImport {

    /** The table's file in the project. */
    public static final String FILE = "platform/value-mappings.json";
    private static final int MAX_XML = 64 * 1024 * 1024;

    private ValueMappingImport() {
    }

    /** One side of a group. */
    record Entry(String agency, String schema, String value, boolean isDefault) {
    }

    /** The groups of a {@code value_mapping.xml}, in file order. */
    static List<List<Entry>> groups(byte[] xml) throws Exception {
        if (xml.length > MAX_XML) {
            throw new IllegalArgumentException("value_mapping.xml is larger than " + (MAX_XML >> 20) + " MB.");
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        if (!"vm".equals(doc.getDocumentElement().getLocalName() != null ? doc.getDocumentElement().getLocalName()
                : doc.getDocumentElement().getTagName())) {
            throw new IllegalArgumentException("value_mapping.xml has no <vm> root element; the export format may have changed.");
        }
        List<List<Entry>> out = new ArrayList<>();
        for (Element group : children(doc.getDocumentElement(), "group")) {
            List<Entry> entries = new ArrayList<>();
            for (Element e : children(group, "entry")) {
                entries.add(new Entry(text(e, "agency"), text(e, "schema"), text(e, "value"), "true".equals(e.getAttribute("isDefault"))));
            }
            out.add(entries);
        }
        return out;
    }

    /** The table's rows for the groups: one per direction, rows towards a default entry first. */
    static List<Map<String, Object>> rows(List<List<Entry>> groups) {
        List<Map<String, Object>> defaults = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        for (List<Entry> g : groups) {
            for (Entry s : g) {
                for (Entry t : g) {
                    if (s == t || (s.agency().equals(t.agency()) && s.schema().equals(t.schema()))) {
                        continue;
                    }
                    Json.Obj row = Json.o().with("sourceAgency", s.agency()).with("sourceIdentifier", s.schema())
                            .with("sourceValue", s.value()).with("targetAgency", t.agency()).with("targetIdentifier", t.schema())
                            .with("targetValue", t.value());
                    if (t.isDefault()) {
                        defaults.add(row.with(DEFAULT_MARK, true));
                    } else {
                        others.add(row);
                    }
                }
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(defaults);
        all.addAll(others);
        return all;
    }

    /**
     * Writes the rows of {@code xml} into the project's table. Rows already there for the same agency and
     * identifier pairs are replaced; with {@code createNew} such rows are an error instead. Returns what changed.
     */
    public static Json.Obj importXml(byte[] xml, Path projectRoot, boolean createNew, String artifact) throws Exception {
        return importGroups(groups(xml), projectRoot, createNew, artifact);
    }

    /**
     * As {@link #importXml} for groups already read. A package's value mappings come as one list: imported one
     * after the other, each would replace the rows of the one before it for the same agencies and identifiers.
     */
    @SuppressWarnings("unchecked")
    static Json.Obj importGroups(List<List<Entry>> groups, Path projectRoot, boolean createNew, String artifact) throws Exception {
        List<Map<String, Object>> rows = rows(groups);
        Set<String> pairs = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            pairs.add(pair(r));
        }
        Path file = projectRoot.resolve(FILE).normalize();
        // The file itself, not only its folder: writing follows a link.
        if (!ProjectPaths.inside(projectRoot, file)) {
            throw new IllegalArgumentException(FILE + " is outside the project (a symbolic link leads outside).");
        }
        List<Object> existing = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            Object parsed = Json.parse(TextFiles.read(file));
            if (!(parsed instanceof List)) {
                throw new IllegalArgumentException(FILE + " is not a JSON array; fix or remove it before the import.");
            }
            existing = (List<Object>) parsed;
        }
        List<Object> kept = new ArrayList<>();
        int replaced = 0;
        for (Object o : existing) {
            if (o instanceof Map && pairs.contains(pair((Map<String, Object>) o))) {
                replaced++;
            } else {
                kept.add(o);
            }
        }
        if (replaced > 0 && createNew) {
            throw new IllegalArgumentException("exists already: " + FILE + " has value mappings for " + String.join(", ", labels(pairs)));
        }
        kept.addAll(rows);
        Files.createDirectories(file.getParent());
        Files.writeString(file, table(kept), StandardCharsets.UTF_8);
        List<Object> notes = new ArrayList<>();
        int ambiguous = ambiguous(rows);
        if (ambiguous > 0) {
            notes.add(ambiguous + " value(s) map to several values without a default; the first group in the file is "
                    + "taken for them (an assumption, docs/fidelity.md).");
        }
        return Json.o().with("artifact", artifact).with("file", FILE).with("groups", groups.size()).with("rows", rows.size())
                .with("replaced", replaced).with("pairs", new ArrayList<Object>(labels(pairs))).with("notes", notes);
    }

    /**
     * Lookups with several answers and none marked default: the same source key towards one target pair, with
     * several rows, whose first row (rows towards a default come first) is not such a row.
     */
    private static int ambiguous(List<Map<String, Object>> rows) {
        Map<String, Integer> count = new LinkedHashMap<>();
        Set<String> defaultFirst = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String key = pair(r) + "|" + r.get("sourceValue");
            if (count.merge(key, 1, Integer::sum) == 1 && Boolean.TRUE.equals(r.get(DEFAULT_MARK))) {
                defaultFirst.add(key);
            }
        }
        return (int) count.entrySet().stream().filter(e -> e.getValue() > 1 && !defaultFirst.contains(e.getKey())).count();
    }

    /** Not written: rows carry it only while the import sorts them. */
    private static final String DEFAULT_MARK = "\u0000default";

    private static String pair(Map<String, Object> r) {
        return r.get("sourceAgency") + "|" + r.get("sourceIdentifier") + "|" + r.get("targetAgency") + "|" + r.get("targetIdentifier");
    }

    private static Set<String> labels(Set<String> pairs) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : pairs) {
            String[] x = p.split("\\|", -1);
            out.add(x[0] + "/" + x[1] + " → " + x[2] + "/" + x[3]);
        }
        return out;
    }

    /** The table as the app writes platform files: an array, one row per line. */
    @SuppressWarnings("unchecked")
    static String table(List<Object> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Object r = rows.get(i);
            if (r instanceof Map) {
                Map<String, Object> clean = new LinkedHashMap<>((Map<String, Object>) r);
                clean.remove(DEFAULT_MARK);
                r = clean;
            }
            sb.append("  ").append(Json.write(r)).append(i + 1 < rows.size() ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element e, String child) {
        List<Element> c = children(e, child);
        return c.isEmpty() ? "" : c.get(0).getTextContent();
    }
}
