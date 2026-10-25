package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
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
import java.util.List;
import java.util.Map;

/**
 * XPath evaluation against the message body. Version 1.0 uses the JDK's XPath engine (as used for
 * XPath expressions in simple iFlow steps); 2.0/3.1 use Saxon. Namespace prefixes are declared like
 * the iFlow's "Namespace Mapping" setting.
 */
public final class XPathStep {

    private final Processor processor = new Processor(false);

    public XPathStep() {
        ProjectResolver.install(processor.getUnderlyingConfiguration());
    }

    /**
     * Request: {@code {"expression": "...", "version": "1.0"|"3.1", "namespaces": {"p": "uri"},
     * "baseDir": "/abs/project", "input": {"body": "..."}}}. {@code doc()}, {@code unparsed-text()} and
     * external entities read files inside {@code baseDir} only.
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
                evalSaxon(expr, ns, input.getBody(), items, resolver);
                result.with("engine", XsltStep.engineVersion() + " (XPath 3.1)");
            }
            result.with("ok", true);
        } catch (Exception e) {
            diags.add(Diagnostic.error(e.getMessage() == null ? e.toString() : e.getMessage()).toJson());
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
        xp.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
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
        });
        XPathEvaluationResult<?> res = xp.evaluateExpression(expr, doc);
        switch (res.type()) {
            case NODESET:
                for (Node n : (XPathNodes) res.value()) {
                    items.add(describeDom(n));
                }
                break;
            case NODE:
                items.add(describeDom((Node) res.value()));
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

    private static Map<String, Object> describeDom(Node n) throws Exception {
        if (n.getNodeType() == Node.ELEMENT_NODE || n.getNodeType() == Node.DOCUMENT_NODE) {
            Transformer t = TransformerFactory.newDefaultInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(n), new StreamResult(w));
            return Json.o().with("kind", "element").with("name", n.getNodeName()).with("value", w.toString());
        }
        return Json.o().with("kind", n.getNodeType() == Node.ATTRIBUTE_NODE ? "attribute" : "text")
                .with("name", n.getNodeName()).with("value", n.getNodeValue());
    }

    private void evalSaxon(String expr, Map<String, String> ns, byte[] body, List<Object> items,
                           ProjectResolver resolver) throws SaxonApiException, TransformerException {
        DocumentBuilder db = processor.newDocumentBuilder();
        XdmNode doc = db.build(resolver.source(new InputSource(new ByteArrayInputStream(body)),
                ProjectResolver.MEMORY_BASE + "input.xml"));
        XPathCompiler xc = processor.newXPathCompiler();
        // Relative URIs in doc() and unparsed-text() resolve against the project folder.
        xc.setBaseURI(URI.create(resolver.resolveAgainst(".", null)));
        ns.forEach(xc::declareNamespace);
        XPathSelector sel = xc.compile(expr).load();
        sel.setURIResolver(resolver);
        sel.setContextItem(doc);
        XdmValue value = sel.evaluate();
        for (XdmItem item : value) {
            if (item.isAtomicValue()) {
                items.add(Json.o().with("kind", "atomic")
                        .with("type", ((XdmAtomicValue) item).getPrimitiveTypeName().getLocalName())
                        .with("value", item.getStringValue()));
            } else if (item instanceof XdmNode) {
                XdmNode node = (XdmNode) item;
                String kind = node.getNodeKind().name().toLowerCase();
                items.add(Json.o().with("kind", kind)
                        .with("name", node.getNodeName() == null ? null : node.getNodeName().toString())
                        .with("value", "element".equals(kind) || "document".equals(kind)
                                ? node.toString() : node.getStringValue()));
            } else {
                items.add(Json.o().with("kind", "item").with("value", item.toString()));
            }
        }
    }

    /** Pretty-prints an XML document (used by the UI's "Format" button). */
    public Map<String, Object> format(Map<String, Object> req) {
        String text = Json.str(req, "text", "");
        try {
            // Formatting reads no files: no project, so external entities fail instead of expanding.
            XdmNode doc = processor.newDocumentBuilder().build(new ProjectResolver(null, null).source(
                    new InputSource(new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    ProjectResolver.MEMORY_BASE + "input.xml"));
            StringWriter w = new StringWriter();
            Serializer s = processor.newSerializer(w);
            s.setOutputProperty(Serializer.Property.INDENT, "yes");
            s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION,
                    text.stripLeading().startsWith("<?xml") ? "no" : "yes");
            s.serializeNode(doc);
            return Json.o().with("ok", true).with("text", w.toString().strip() + "\n");
        } catch (SaxonApiException | TransformerException e) {
            return Json.o().with("ok", false).with("diagnostics", List.of(Diagnostic.error(e.getMessage()).toJson()));
        }
    }
}
