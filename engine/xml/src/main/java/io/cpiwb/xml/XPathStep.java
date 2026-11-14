package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathEvaluationResult;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * XPath evaluation against the message body. Version 1.0 uses the JDK's XPath engine, 3.1 uses Saxon, as
 * SAP documents for the Content Modifier and the Filter; Router conditions in real iFlows use XPath 2.0+ syntax
 * (F37), and 3.1 there is an assumption. Namespace prefixes are declared
 * like the iFlow's "Namespace Mapping" setting.
 */
public final class XPathStep {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][\\w.-]*");
    private final Processor processor = new Processor(false);

    public XPathStep() {
        ProjectResolver.install(processor.getUnderlyingConfiguration());
        ProjectResolver.hideEnvironment(processor.getUnderlyingConfiguration());
    }

    /**
     * Request: {@code {"expression": "...", "version": "1.0"|"3.1", "namespaces": {"p": "uri"},
     * "baseDir": "/abs/project", "input": {"body": "..."}}}. {@code doc()}, {@code unparsed-text()} and
     * external entities read files inside {@code baseDir} only.
     * <p>With {@code "as"} (XPath 3.1 only) the result also has one {@code value}, as the Flow Steps use it:
     * {@code "boolean"} the effective boolean value (a Router condition), {@code "string"} the string value
     * of the first item, {@code "nodes"} all items serialized (Content Modifier XPath with a DOM data type).
     */
    public synchronized Map<String, Object> run(Map<String, Object> req) {
        String baseDirStr = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(
                baseDirStr == null || baseDirStr.isBlank() ? null : Path.of(baseDirStr), null);
        resolver.begin();
        try {
            return run(req, resolver);
        } finally {
            ProjectResolver.end();
        }
    }

    private Map<String, Object> run(Map<String, Object> req, ProjectResolver resolver) {
        long t0 = System.nanoTime();
        String expr = Json.str(req, "expression", "");
        String version = Json.str(req, "version", "1.0");
        Map<String, String> ns = XsltStep.stringMap(Json.obj(req, "namespaces"));
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        Json.Obj result = Json.o();
        List<Object> items = new ArrayList<>();
        List<Object> diags = new ArrayList<>();
        try {
            if ("1.0".equals(version)) {
                evalJdk(expr, ns, input.getBody(), items, resolver);
                result.with("engine", "JDK XPath 1.0");
            } else {
                String value = evalSaxon(expr, ns, input, items, resolver, Json.str(req, "as", null));
                if (value != null) {
                    result.with("value", value);
                }
                result.with("engine", XsltStep.engineVersion() + " (XPath 3.1)");
            }
            result.with("ok", true);
        } catch (Exception e) {
            diags.add(Diagnostic.error(XsltStep.message(e)).toJson());
            result.with("ok", false);
        }
        result.with("items", items).with("count", items.size()).with("diagnostics", diags);
        result.with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
        return result;
    }

    private static void evalJdk(String expr, Map<String, String> ns, byte[] body, List<Object> items,
                                ProjectResolver resolver) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver(resolver);
        InputSource in = new InputSource(new ByteArrayInputStream(body));
        in.setSystemId(ProjectResolver.MEMORY_BASE + "input.xml");
        Document doc = builder.parse(in);
        XPath xp = XPathFactory.newDefaultInstance().newXPath();
        xp.setNamespaceContext(namespaceContext(ns));
        XPathEvaluationResult<?> res = xp.evaluateExpression(expr, doc);
        Map<Node, Integer> positions = new java.util.IdentityHashMap<>();
        switch (res.type()) {
            case NODESET:
                for (Node n : (XPathNodes) res.value()) {
                    items.add(withPlace(describeDom(n), domPlace(n, positions)));
                }
                break;
            case NODE:
                items.add(withPlace(describeDom((Node) res.value()), domPlace((Node) res.value(), positions)));
                break;
            case NUMBER:
                Object num = res.value();
                double d = ((Number) num).doubleValue();
                items.add(Json.o().with("kind", "atomic").with("type", "number")
                        .with("value", d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d)));
                break;
            case BOOLEAN:
                items.add(Json.o().with("kind", "atomic").with("type", "boolean").with("value", String.valueOf(res.value())));
                break;
            default:
                items.add(Json.o().with("kind", "atomic").with("type", "string").with("value", String.valueOf(res.value())));
        }
    }

    /** The iFlow's namespace mapping (prefix to URI) for the JDK's XPath. */
    static NamespaceContext namespaceContext(Map<String, String> ns) {
        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                // The xml prefix is bound by definition (Namespaces in XML), as XPath 3.1 has it without a mapping.
                if ("xml".equals(prefix) && !ns.containsKey(prefix)) {
                    return javax.xml.XMLConstants.XML_NS_URI;
                }
                return ns.getOrDefault(prefix, "");
            }

            @Override
            public String getPrefix(String uri) {
                return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey)
                        .findFirst().orElse(null);
            }

            @Override
            public Iterator<String> getPrefixes(String uri) {
                return ns.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey).iterator();
            }
        };
    }

    // ---- where a node is in the input document, for the UI to mark it in the text ("at")
    // {path: [1, 3, 2], name: "ns0:Item"} is the element that is the 2nd element child of the 3rd element child
    // of the root element, with its name as written; an attribute adds {attribute: "sku"} as written, text,
    // comments and processing instructions {text: true} on their parent element. Nodes of another document
    // (doc()) and the document node get none.

    private static Map<String, Object> withPlace(Map<String, Object> item, Map<String, Object> place) {
        if (place != null) {
            item.put("at", place);
        }
        return item;
    }

    /**
     * The element's position among its parent's element children. Each parent's children are counted once per
     * run, into {@code positions}: counting siblings for every node would be quadratic in a list of thousands.
     */
    private static int domPosition(Node e, Map<Node, Integer> positions) {
        Integer known = positions.get(e);
        if (known != null) {
            return known;
        }
        Node parent = e.getParentNode();
        int i = 0;
        for (Node c = parent == null ? e : parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                positions.put(c, ++i);
            }
            if (parent == null) {
                break;
            }
        }
        return positions.getOrDefault(e, 1);
    }

    private static Map<String, Object> domPlace(Node n, Map<Node, Integer> positions) {
        Json.Obj at = Json.o();
        if (n.getNodeType() == Node.ATTRIBUTE_NODE) {
            at.with("attribute", n.getNodeName());
            n = ((org.w3c.dom.Attr) n).getOwnerElement();
        } else if (n.getNodeType() != Node.ELEMENT_NODE) {
            at.with("text", true);
            n = n.getParentNode();
        }
        if (n == null || n.getNodeType() != Node.ELEMENT_NODE) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        String name = n.getNodeName();
        for (Node e = n; e != null && e.getNodeType() == Node.ELEMENT_NODE; e = e.getParentNode()) {
            path.add(0, domPosition(e, positions));
        }
        return at.with("path", path).with("name", name);
    }

    /** As {@link #domPosition}, for Saxon's tree. */
    private static int saxonPosition(XdmNode e, Map<XdmNode, Integer> positions) {
        Integer known = positions.get(e);
        if (known != null) {
            return known;
        }
        XdmNode parent = e.getParent();
        if (parent == null) {
            return 1;
        }
        int i = 0;
        for (Iterator<XdmNode> it = parent.axisIterator(Axis.CHILD); it.hasNext(); ) {
            XdmNode c = it.next();
            if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
                positions.put(c, ++i);
            }
        }
        return positions.getOrDefault(e, 1);
    }

    private static Map<String, Object> saxonPlace(XdmNode n, XdmNode doc, Map<XdmNode, Integer> positions) {
        if (!doc.equals(n.getRoot())) {
            return null; // a node of another document (doc())
        }
        Json.Obj at = Json.o();
        XdmNodeKind kind = n.getNodeKind();
        if (kind == XdmNodeKind.NAMESPACE) {
            return null; // in scope on an element, often declared on an ancestor: no single place in the text
        }
        if (kind == XdmNodeKind.ATTRIBUTE) {
            at.with("attribute", n.getUnderlyingNode().getDisplayName());
            n = n.getParent();
        } else if (kind != XdmNodeKind.ELEMENT) {
            at.with("text", true);
            n = n.getParent();
        }
        if (n == null || n.getNodeKind() != XdmNodeKind.ELEMENT) {
            return null;
        }
        List<Integer> path = new ArrayList<>();
        String name = n.getUnderlyingNode().getDisplayName();
        for (XdmNode e = n; e != null && e.getNodeKind() == XdmNodeKind.ELEMENT; e = e.getParent()) {
            path.add(0, saxonPosition(e, positions));
        }
        return at.with("path", path).with("name", name);
    }

    private static Map<String, Object> describeDom(Node n) throws Exception {
        if (n.getNodeType() == Node.ELEMENT_NODE || n.getNodeType() == Node.DOCUMENT_NODE) {
            return Json.o().with("kind", "element").with("name", n.getNodeName())
                    .with("value", io.cpiwb.core.DomXml.serialize(n));
        }
        return Json.o().with("kind", n.getNodeType() == Node.ATTRIBUTE_NODE ? "attribute" : "text")
                .with("name", n.getNodeName()).with("value", n.getNodeValue());
    }

    private String evalSaxon(String expr, Map<String, String> ns, SimExchange input, List<Object> items,
                             ProjectResolver resolver, String as) throws SaxonApiException, TransformerException {
        DocumentBuilder db = processor.newDocumentBuilder();
        XdmNode doc = db.build(resolver.source(new InputSource(new ByteArrayInputStream(input.getBody())),
                ProjectResolver.MEMORY_BASE + "input.xml"));
        XPathCompiler xc = processor.newXPathCompiler();
        // Relative URIs in doc() and unparsed-text() resolve against the project folder.
        xc.setBaseURI(URI.create(resolver.resolveAgainst(".", null)));
        ns.forEach(xc::declareNamespace);
        XPathSelector sel = compile(xc, expr, input);
        sel.setURIResolver(resolver);
        sel.setContextItem(doc);
        if ("boolean".equals(as)) {
            return String.valueOf(sel.effectiveBooleanValue());
        }
        XdmValue value = sel.evaluate();
        Map<XdmNode, Integer> positions = new java.util.HashMap<>();
        for (XdmItem item : value) {
            if (item.isAtomicValue()) {
                items.add(Json.o().with("kind", "atomic")
                        .with("type", ((XdmAtomicValue) item).getPrimitiveTypeName().getLocalName())
                        .with("value", item.getStringValue()));
            } else if (item instanceof XdmNode) {
                XdmNode node = (XdmNode) item;
                String kind = node.getNodeKind().name().toLowerCase();
                items.add(withPlace(Json.o().with("kind", kind)
                        .with("name", node.getNodeName() == null ? null : node.getNodeName().toString())
                        .with("value", "element".equals(kind) || "document".equals(kind)
                                ? node.toString() : node.getStringValue()), saxonPlace(node, doc, positions)));
            } else {
                items.add(Json.o().with("kind", "item").with("value", item.toString()));
            }
        }
        if ("string".equals(as)) {
            return value.size() == 0 ? "" : value.itemAt(0).getStringValue();
        }
        if ("nodes".equals(as)) {
            // The nodes as they are in the body: XdmNode.toString() indents, which would add whitespace text nodes
            // to the Content Modifier's DOM value.
            StringBuilder all = new StringBuilder();
            for (XdmItem item : value) {
                all.append(item instanceof XdmNode ? serialize((XdmNode) item) : item.getStringValue());
            }
            return all.toString();
        }
        return null;
    }

    private String serialize(XdmNode node) throws SaxonApiException {
        java.io.StringWriter w = new java.io.StringWriter();
        net.sf.saxon.s9api.Serializer ser = processor.newSerializer(w);
        ser.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.METHOD, "xml");
        ser.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.INDENT, "no");
        ser.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.OMIT_XML_DECLARATION, "yes");
        ser.serializeNode(node);
        return w.toString();
    }

    /**
     * Compiles the XPath with the message's headers and properties as variables: {@code $name} is the header
     * of that name, else the exchange property, as Camel's XPath variable resolver (MessageVariableResolver,
     * Camel 3.14.7) does for CPI's Filter, Router and Content Modifier. Real iFlows use it, e.g.
     * {@code Student[Age > $AgeLimit_Header]}.
     */
    static XPathSelector compile(XPathCompiler xc, String expr, SimExchange input) throws SaxonApiException {
        Map<String, Object> vars = new LinkedHashMap<>();
        input.getProperties().forEach((k, v) -> putVariable(vars, k, v));
        input.getHeaders().forEach((k, v) -> putVariable(vars, k, v));
        for (String name : vars.keySet()) {
            xc.declareVariable(new QName(name));
        }
        io.cpiwb.xml.compat.SaxonCompat.withoutTransform(xc);
        XPathSelector sel = xc.compile(expr).load();
        for (Map.Entry<String, Object> v : vars.entrySet()) {
            Object o = v.getValue();
            XdmAtomicValue value;
            try {
                value = XdmAtomicValue.makeAtomicValue(o);
            } catch (IllegalArgumentException e) {
                value = new XdmAtomicValue(String.valueOf(o));
            }
            sel.setVariable(new QName(v.getKey()), value);
        }
        return sel;
    }

    private static void putVariable(Map<String, Object> vars, String name, Object value) {
        if (value != null && name != null && NAME.matcher(name).matches()) {
            vars.put(name, value);
        }
    }

    /** Pretty-prints an XML document (used by the UI's "Format" button). */
    public Map<String, Object> format(Map<String, Object> req) {
        String text = Json.str(req, "text", "");
        try {
            // Formatting reads no files: no project, so external entities fail instead of expanding.
            XdmNode doc = processor.newDocumentBuilder().build(new ProjectResolver(null, null).source(
                    new InputSource(new java.io.StringReader(text)), ProjectResolver.MEMORY_BASE + "input.xml"));
            StringWriter w = new StringWriter();
            Serializer s = processor.newSerializer(w);
            s.setOutputProperty(Serializer.Property.INDENT, "yes");
            // The declaration keeps the encoding it names; characters that encoding lacks become references.
            java.nio.charset.Charset declared = io.cpiwb.core.TextFiles.declaredCharset(text);
            if (declared != null) {
                s.setOutputProperty(Serializer.Property.ENCODING, declared.name());
            }
            s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION,
                    text.stripLeading().startsWith("<?xml") ? "no" : "yes");
            s.serializeNode(doc);
            return Json.o().with("ok", true).with("text", w.toString().strip() + "\n");
        } catch (SaxonApiException | TransformerException e) {
            return Json.o().with("ok", false).with("diagnostics", List.of(Diagnostic.error(e.getMessage()).toJson()));
        }
    }
}
