package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Runs test cases stored as {@code tests/<name>/case.json}. A case describes one step execution
 * (input message, mapping, options) plus optional expectations; see docs/test-cases.md.
 */
public final class TestRunner {

    /** Turns a case file into an RPC request for one step type. */
    public interface StepAdapter {
        /** Returns the RPC method name and fills {@code params}. */
        String buildRequest(Map<String, Object> testCase, Path caseDir, Path projectRoot,
                            Map<String, Object> params) throws IOException;
    }

    public static final class CaseResult {
        public final String name;
        public final Path dir;
        public final String status; // passed | failed | error | skipped
        public final List<String> messages;
        public final long millis;

        CaseResult(String name, Path dir, String status, List<String> messages, long millis) {
            this.name = name;
            this.dir = dir;
            this.status = status;
            this.messages = messages;
            this.millis = millis;
        }

        public Map<String, Object> toJson() {
            return Json.o().with("name", name).with("dir", dir.toString()).with("status", status)
                    .with("messages", messages).with("ms", millis);
        }
    }

    private final RpcHandler handler;
    private final Map<String, StepAdapter> adapters = new LinkedHashMap<>();
    private final String engineProfile;

    public TestRunner(RpcHandler handler, String engineProfile) {
        this.handler = handler;
        this.engineProfile = engineProfile;
    }

    public TestRunner register(String step, StepAdapter adapter) {
        adapters.put(step, adapter);
        return this;
    }

    /** Finds all case.json files below {@code root} (or root itself if it is a case file/dir). */
    public static List<Path> findCases(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        if (Files.isRegularFile(root.resolve("case.json"))) {
            return List.of(root.resolve("case.json"));
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals("case.json")).sorted().toList();
        }
    }

    /** Project root: nearest ancestor containing cpi-workbench.json, else the case directory. */
    public static Path projectRoot(Path caseDir) {
        for (Path p = caseDir.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("cpi-workbench.json"))) {
                return p;
            }
        }
        return caseDir.toAbsolutePath();
    }

    public CaseResult run(Path caseFile) {
        long start = System.nanoTime();
        Path dir = caseFile.toAbsolutePath().getParent();
        String name = dir.getFileName().toString();
        List<String> msgs = new ArrayList<>();
        try {
            Map<String, Object> tc = Json.parseObject(Files.readString(caseFile, StandardCharsets.UTF_8));
            name = Json.str(tc, "name", name);
            String profile = Json.str(tc, "profile", null);
            if (profile != null && engineProfile != null && !profile.equals(engineProfile)) {
                msgs.add("Profile '" + profile + "' is handled by another engine (this engine: " + engineProfile + ")");
                return new CaseResult(name, dir, "skipped", msgs, elapsed(start));
            }
            String step = Json.str(tc, "step", "xslt");
            StepAdapter adapter = adapters.get(step);
            if (adapter == null) {
                msgs.add("Step type '" + step + "' is not supported by this engine");
                return new CaseResult(name, dir, "skipped", msgs, elapsed(start));
            }
            Map<String, Object> params = new LinkedHashMap<>();
            String method = adapter.buildRequest(tc, dir, projectRoot(dir), params);
            Object raw = handler.call(method, params);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) raw;
            check(tc, result, dir, msgs);
            return new CaseResult(name, dir, msgs.isEmpty() ? "passed" : "failed", msgs, elapsed(start));
        } catch (Exception e) {
            msgs.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            return new CaseResult(name, dir, "error", msgs, elapsed(start));
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void check(Map<String, Object> tc, Map<String, Object> result, Path dir, List<String> msgs)
            throws IOException {
        Map<String, Object> expect = Json.obj(tc, "expect");
        boolean ok = Json.bool(result, "ok", false);
        boolean expectOk = expect == null || Json.bool(expect, "ok", true);
        if (ok != expectOk) {
            msgs.add(expectOk ? "Step failed: " + firstError(result) : "Step succeeded but a failure was expected");
            return;
        }
        if (expect == null) {
            return;
        }
        if (expect.containsKey("valid")) {
            boolean valid = Json.bool(result, "valid", false);
            if (valid != Json.bool(expect, "valid", true)) {
                msgs.add("Expected valid=" + expect.get("valid") + " but was " + valid);
            }
        }
        if (expect.containsKey("errorContains")) {
            String needle = Json.str(expect, "errorContains", "");
            if (!Json.write(result.get("diagnostics")).contains(needle)) {
                msgs.add("Expected an error containing '" + needle + "'");
            }
        }
        if (expect.containsKey("body")) {
            String expected = readText(expect.get("body"), dir);
            String actual = Json.str(Json.obj(result, "output"), "body", "");
            String mode = Json.str(expect, "compare", "xml");
            String diff = compare(expected, actual, mode);
            if (diff != null) {
                msgs.add("Body differs (" + mode + "): " + diff);
            }
        }
        compareValues("header", Json.obj(expect, "headers"), Json.arr(Json.obj(result, "output"), "headers"), msgs);
        compareValues("property", Json.obj(expect, "properties"), Json.arr(Json.obj(result, "output"), "properties"), msgs);
        if (expect.containsKey("result")) {
            String actual = Json.str(result, "result", null);
            if (!Objects.equals(Json.str(expect, "result", null), actual)) {
                msgs.add("Expected result '" + expect.get("result") + "' but was '" + actual + "'");
            }
        }
    }

    private static String firstError(Map<String, Object> result) {
        for (Object d : Json.arr(result, "diagnostics")) {
            if (d instanceof Map && "error".equals(((Map<?, ?>) d).get("severity"))) {
                return String.valueOf(((Map<?, ?>) d).get("message"));
            }
        }
        return "unknown error";
    }

    @SuppressWarnings("unchecked")
    private static void compareValues(String kind, Map<String, Object> expected, List<Object> actualList,
                                      List<String> msgs) {
        if (expected == null) {
            return;
        }
        Map<String, Map<String, Object>> actual = new LinkedHashMap<>();
        for (Object o : actualList) {
            Map<String, Object> m = (Map<String, Object>) o;
            actual.put(Json.str(m, "name", ""), m);
        }
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            Map<String, Object> a = actual.get(e.getKey());
            Object exp = e.getValue();
            if (exp == null) {
                if (a != null) {
                    msgs.add("Expected " + kind + " '" + e.getKey() + "' to be absent");
                }
                continue;
            }
            if (a == null) {
                msgs.add("Missing " + kind + " '" + e.getKey() + "'");
                continue;
            }
            String expValue = exp instanceof Map ? Json.str((Map<String, Object>) exp, "value", null) : exp.toString();
            String expType = exp instanceof Map ? Json.str((Map<String, Object>) exp, "type", null) : null;
            if (!Objects.equals(expValue, Json.str(a, "value", null))) {
                msgs.add(kind + " '" + e.getKey() + "': expected '" + expValue + "' but was '" + a.get("value") + "'");
            }
            if (expType != null && !expType.equals(Json.str(a, "type", null))) {
                msgs.add(kind + " '" + e.getKey() + "': expected type " + expType + " but was " + a.get("type"));
            }
        }
    }

    /** Reads a body reference: a string (inline text), {"file": "..."} or {"text": "..."}. */
    @SuppressWarnings("unchecked")
    public static String readText(Object ref, Path dir) throws IOException {
        if (ref == null) {
            return "";
        }
        if (ref instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) ref;
            if (m.get("file") != null) {
                return Files.readString(dir.resolve(m.get("file").toString()), StandardCharsets.UTF_8);
            }
            return Json.str(m, "text", "");
        }
        return ref.toString();
    }

    /** Returns null if equal, otherwise a short description of the first difference. */
    public static String compare(String expected, String actual, String mode) {
        if ("text".equals(mode)) {
            return expected.equals(actual) ? null : firstLineDiff(expected, actual);
        }
        if ("trimmed".equals(mode)) {
            return expected.strip().equals(actual.strip()) ? null : firstLineDiff(expected.strip(), actual.strip());
        }
        try {
            Document e = parseNormalized(expected);
            Document a = parseNormalized(actual);
            if (e.isEqualNode(a)) {
                return null;
            }
            return describeNodeDiff(e.getDocumentElement(), a.getDocumentElement(), "/");
        } catch (Exception ex) {
            return "cannot compare as XML (" + ex.getMessage() + "); use \"compare\": \"text\"";
        }
    }

    private static Document parseNormalized(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        Document d = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        stripWhitespace(d);
        d.normalizeDocument();
        return d;
    }

    private static void stripWhitespace(Node node) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            // Namespace declarations are not content; prefixes/URIs are compared on the nodes themselves.
            org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
            for (int i = attrs.getLength() - 1; i >= 0; i--) {
                Node a = attrs.item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI())) {
                    ((org.w3c.dom.Element) node).removeAttributeNode((org.w3c.dom.Attr) a);
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node c = children.item(i);
            if ((c.getNodeType() == Node.TEXT_NODE && c.getNodeValue().isBlank())
                    || c.getNodeType() == Node.COMMENT_NODE) {
                node.removeChild(c);
            } else {
                stripWhitespace(c);
            }
        }
    }

    private static String describeNodeDiff(Node e, Node a, String path) {
        if (e == null || a == null) {
            return "at " + path + ": " + (e == null ? "unexpected node " + a.getNodeName() : "missing node " + e.getNodeName());
        }
        String here = path + (e.getNodeType() == Node.ELEMENT_NODE ? e.getNodeName() : "");
        if (e.getNodeType() != a.getNodeType() || !Objects.equals(e.getLocalName(), a.getLocalName())
                || !Objects.equals(e.getNamespaceURI(), a.getNamespaceURI())) {
            return "at " + path + ": expected <" + e.getNodeName() + "> but found <" + a.getNodeName() + ">";
        }
        if (e.getNodeType() == Node.TEXT_NODE || e.getNodeType() == Node.CDATA_SECTION_NODE) {
            return "at " + path + ": expected text '" + e.getNodeValue() + "' but found '" + a.getNodeValue() + "'";
        }
        if (e.getAttributes() != null && !sameAttributes(e, a)) {
            return "at " + here + ": attributes differ";
        }
        NodeList ec = e.getChildNodes();
        NodeList ac = a.getChildNodes();
        int n = Math.max(ec.getLength(), ac.getLength());
        for (int i = 0; i < n; i++) {
            Node x = i < ec.getLength() ? ec.item(i) : null;
            Node y = i < ac.getLength() ? ac.item(i) : null;
            if (x == null || y == null || !x.isEqualNode(y)) {
                return describeNodeDiff(x, y, here + "/");
            }
        }
        return "at " + here + ": nodes differ";
    }

    private static boolean sameAttributes(Node e, Node a) {
        if (e.getAttributes().getLength() != a.getAttributes().getLength()) {
            return false;
        }
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node ea = e.getAttributes().item(i);
            Node aa = ea.getNamespaceURI() == null
                    ? a.getAttributes().getNamedItem(ea.getNodeName())
                    : a.getAttributes().getNamedItemNS(ea.getNamespaceURI(), ea.getLocalName());
            if (aa == null || !Objects.equals(ea.getNodeValue(), aa.getNodeValue())) {
                return false;
            }
        }
        return true;
    }

    private static String firstLineDiff(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String x = i < e.length ? e[i] : "<end>";
            String y = i < a.length ? a[i] : "<end>";
            if (!x.equals(y)) {
                return "line " + (i + 1) + ": expected '" + x + "' but found '" + y + "'";
            }
        }
        return "texts differ";
    }
}
