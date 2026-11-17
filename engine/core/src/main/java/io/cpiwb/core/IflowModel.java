package io.cpiwb.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The whole of an imported iFlow, for the app's iFlow list and its Diagram tab: processes, every element (also
 * those the simulator does not run), the flows between them and the editor's layout. Read from the {@code .iflw}
 * as public exports show it (docs/plan.md F68); the format is not published, so this is best effort and nothing
 * here is simulated. Elements keep their ids, which is how the app finds the step it can open.
 */
public final class IflowModel {

    /** Property values are cut at this length: a routing table or schedule can hold a whole XML document. */
    static final int MAX_VALUE = 2000;
    /** BPMN elements that are no flow nodes. */
    private static final Set<String> NOT_NODES = Set.of("sequenceFlow", "extensionElements", "incoming", "outgoing",
            "documentation", "laneSet", "standardLoopCharacteristics", "multiInstanceLoopCharacteristics", "dataObject",
            "dataObjectReference", "dataStoreReference", "association", "textAnnotation");

    private IflowModel() {
    }

    /** {@code iflow.models}: one model per {@code .iflw} under iflows/, or its error. */
    public static Map<String, Object> models(Path projectRoot) throws IOException {
        List<Object> out = new ArrayList<>();
        Path iflows = projectRoot.resolve("iflows");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(iflows)) {
            try (Stream<Path> s = Files.walk(iflows, 12)) {
                s.filter(p -> p.toString().endsWith(".iflw")).sorted().forEach(files::add);
            }
        }
        for (Path file : files) {
            Path rel = iflows.relativize(file);
            String iflow = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
            String path = projectRoot.relativize(file).toString().replace('\\', '/');
            try {
                out.add(read(file).with("iflow", iflow).with("file", path));
            } catch (Exception ex) {
                out.add(Json.o().with("iflow", iflow).with("file", path).with("error", "Cannot read the iFlow model: " + ex.getMessage()));
            }
        }
        return Json.o().with("models", out);
    }

    static Json.Obj read(Path file) throws Exception {
        return read(file, MAX_VALUE);
    }

    /** The model with property values cut at {@code maxValue} characters ({@link IflowIndex} reads them whole). */
    static Json.Obj read(Path file, int maxValue) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(file.toFile());
        Element root = doc.getDocumentElement();
        Map<String, double[]> bounds = new LinkedHashMap<>();
        Map<String, List<Object>> waypoints = new LinkedHashMap<>();
        layout(root, bounds, waypoints);

        List<Object> processes = new ArrayList<>();
        List<Object> nodes = new ArrayList<>();
        List<Object> flows = new ArrayList<>();
        Map<String, String> pools = new LinkedHashMap<>(); // process id -> participant id
        String name = file.getFileName().toString().replaceFirst("\\.iflw$", "");
        for (Element collab : children(root, "collaboration")) {
            for (Element p : children(collab, "participant")) {
                String type = p.getAttributeNS("http:///com.sap.ifl.model/Ifl.xsd", "type");
                if (type.isEmpty()) {
                    type = props(p).getOrDefault("ifl:type", "");
                }
                if (!p.getAttribute("processRef").isEmpty()) {
                    pools.put(p.getAttribute("processRef"), p.getAttribute("id"));
                    continue;
                }
                // EndpointRecevier is how the editor spells it (F68).
                String kind = type.toLowerCase(Locale.ROOT).startsWith("endpointrec") ? "receiver"
                        : type.equalsIgnoreCase("EndpointSender") ? "sender" : "participant";
                nodes.add(node(p, kind, null, bounds, maxValue));
            }
            for (Element m : children(collab, "messageFlow")) {
                Map<String, String> pr = props(m);
                flows.add(flow(m, "message", waypoints).with("adapter", pr.getOrDefault("ComponentType", m.getAttribute("name")))
                        .with("direction", pr.get("direction")).with("properties", limited(pr, maxValue)));
            }
        }
        for (Element process : children(root, "process")) {
            String id = process.getAttribute("id");
            String variant = props(process).getOrDefault("cmdVariantUri", "");
            String kind = variant.contains("LocalIntegrationProcess") ? "local" : "main";
            String pool = pools.get(id);
            processes.add(Json.o().with("id", id).with("name", process.getAttribute("name")).with("kind", kind)
                    .with("pool", pool).with("bounds", pool == null ? null : box(bounds.get(pool))));
            elements(process, id, processes, nodes, flows, bounds, waypoints, maxValue);
        }
        return Json.o().with("name", name).with("processes", processes).with("nodes", nodes).with("flows", flows)
                .with("hasLayout", !bounds.isEmpty());
    }

    private static void elements(Element container, String process, List<Object> processes, List<Object> nodes,
                                 List<Object> flows, Map<String, double[]> bounds, Map<String, List<Object>> waypoints, int maxValue) {
        Map<String, String> defaults = new LinkedHashMap<>(); // flow id -> gateway id
        for (Element el : children(container, null)) {
            String ln = local(el);
            if ("sequenceFlow".equals(ln)) {
                Element cond = first(el, "conditionExpression");
                Map<String, String> pr = props(el);
                flows.add(flow(el, "sequence", waypoints).with("condition", cond == null ? null : cond.getTextContent().trim())
                        .with("expressionType", pr.get("expressionType")));
                continue;
            }
            if (NOT_NODES.contains(ln) || el.getAttribute("id").isEmpty()) {
                continue;
            }
            Map<String, String> pr = props(el);
            if ("subProcess".equals(ln)) {
                // An Exception Subprocess sits inside its process, connected to it by no flow (F68).
                boolean exception = "ErrorEventSubProcessTemplate".equals(pr.get("activityType"))
                        || "true".equals(el.getAttribute("triggeredByEvent"));
                String id = el.getAttribute("id");
                processes.add(Json.o().with("id", id).with("name", el.getAttribute("name"))
                        .with("kind", exception ? "exception" : "subprocess").with("parent", process)
                        .with("bounds", box(bounds.get(id))));
                nodes.add(node(el, "subprocess", process, bounds, maxValue));
                elements(el, id, processes, nodes, flows, bounds, waypoints, maxValue);
                continue;
            }
            String kind = ln.equals("startEvent") ? "start" : ln.equals("endEvent") ? "end"
                    : ln.endsWith("Event") ? "event" : ln.endsWith("Gateway") ? "gateway" : "step";
            Json.Obj n = node(el, kind, process, bounds, maxValue);
            if (!el.getAttribute("default").isEmpty()) {
                defaults.put(el.getAttribute("default"), el.getAttribute("id"));
            }
            for (String def : List.of("errorEventDefinition", "messageEventDefinition", "timerEventDefinition",
                    "terminateEventDefinition", "escalationEventDefinition")) {
                if (first(el, def) != null) {
                    n.with("event", def.replace("EventDefinition", ""));
                }
            }
            // Process Call and Looping Process Call name the local process they run (F68).
            if (pr.containsKey("processId")) {
                n.with("calls", pr.get("processId"));
            }
            if (first(el, "standardLoopCharacteristics") != null || "LoopingProcess".equals(pr.get("subActivityType"))) {
                n.with("loop", true);
            }
            nodes.add(n);
        }
        // The Router's default route is the gateway's default attribute; the flow has no condition then.
        for (Object o : flows) {
            Json.Obj fl = (Json.Obj) o;
            if (defaults.containsKey(fl.get("id"))) {
                fl.with("default", true);
            }
        }
    }

    private static Json.Obj node(Element el, String kind, String process, Map<String, double[]> bounds, int maxValue) {
        Map<String, String> pr = props(el);
        String id = el.getAttribute("id");
        return Json.o().with("id", id).with("name", el.getAttribute("name")).with("kind", kind).with("element", local(el))
                .with("process", process).with("activityType", pr.get("activityType"))
                .with("subActivityType", pr.get("subActivityType")).with("variant", variant(pr.get("cmdVariantUri")))
                .with("bounds", box(bounds.get(id))).with("properties", limited(pr, maxValue));
    }

    private static Json.Obj flow(Element el, String kind, Map<String, List<Object>> waypoints) {
        String id = el.getAttribute("id");
        return Json.o().with("id", id).with("kind", kind).with("name", el.getAttribute("name"))
                .with("source", el.getAttribute("sourceRef")).with("target", el.getAttribute("targetRef"))
                .with("waypoints", waypoints.getOrDefault(id, List.of()));
    }

    /** The step's variant name from cmdVariantUri ({@code ctype::FlowstepVariant/cname::put/version::1.6.0} → put). */
    static String variant(String uri) {
        if (uri == null) {
            return null;
        }
        for (String part : uri.split("/")) {
            if (part.startsWith("cname::")) {
                return part.substring(7);
            }
        }
        return null;
    }

    private static void layout(Element root, Map<String, double[]> bounds, Map<String, List<Object>> waypoints) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element diagram) || !"BPMNDiagram".equals(local(diagram))) {
                continue;
            }
            for (Element plane : children(diagram, "BPMNPlane")) {
                for (Element shape : children(plane, "BPMNShape")) {
                    Element b = first(shape, "Bounds");
                    if (b != null) {
                        bounds.put(shape.getAttribute("bpmnElement"), new double[] {
                            num(b, "x"), num(b, "y"), num(b, "width"), num(b, "height")});
                    }
                }
                for (Element edge : children(plane, "BPMNEdge")) {
                    List<Object> pts = new ArrayList<>();
                    for (Element w : children(edge, "waypoint")) {
                        pts.add(List.of(num(w, "x"), num(w, "y")));
                    }
                    waypoints.put(edge.getAttribute("bpmnElement"), pts);
                }
            }
        }
    }

    private static double num(Element e, String attr) {
        try {
            return Double.parseDouble(e.getAttribute(attr));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Json.Obj box(double[] b) {
        return b == null ? null : Json.o().with("x", b[0]).with("y", b[1]).with("width", b[2]).with("height", b[3]);
    }

    /** The element's own properties (its extensionElements), not those of the elements inside it. */
    static Map<String, String> props(Element el) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element ext : children(el, "extensionElements")) {
            for (Element p : children(ext, "property")) {
                Element k = first(p, "key");
                Element v = first(p, "value");
                if (k != null) {
                    out.put(k.getTextContent().trim(), v == null ? "" : v.getTextContent().trim());
                }
            }
        }
        return out;
    }

    private static Map<String, Object> limited(Map<String, String> props, int maxValue) {
        Map<String, Object> out = new LinkedHashMap<>();
        props.forEach((k, v) -> out.put(k, v.length() > maxValue ? v.substring(0, maxValue) + "…" : v));
        return out;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name == null || name.equals(local(e)))) {
                out.add(e);
            }
        }
        return out;
    }

    /** The first direct child of that name. */
    private static Element first(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String local(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }
}
