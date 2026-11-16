package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.crypto.NodeSetData;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Message Digest step (SAP: "Define Message Digest", docs/plan.md F63). The message, or the part a filter
 * XPath selects, is turned into canonical XML; the digest of that is written, Base64-encoded, to a header
 * (default {@code SAPMessageDigest}). The body stays as it is.
 * <p>
 * The canonical forms are the W3C's, through the JDK's XML signature API. Not documented by SAP, and
 * therefore assumptions or refused here until a tenant shows it (Q-E1):
 * <ul>
 * <li><i>None</i>: assumed to hash the body's bytes as they are. With a filter it is refused: how the
 * selected part is written without canonicalization is unknown.</li>
 * <li>A filter that selects several nodes or none is refused. The filter is XPath 1.0 here; which version
 * CPI evaluates is unknown.</li>
 * <li>The header is assumed to be a String.</li>
 * </ul>
 */
public final class DigestStep {

    /** The tab's names and the W3C's identifiers of the canonicalization methods SAP lists. */
    private static final Map<String, String> METHODS = new LinkedHashMap<>();
    /** SAP's names of the hash algorithms (an exported iFlow writes SHA-512) and the JDK's. */
    private static final Map<String, String> ALGORITHMS = Map.of("MD5", "MD5", "SHA1", "SHA-1", "SHA256", "SHA-256",
            "SHA384", "SHA-384", "SHA512", "SHA-512");

    static {
        METHODS.put("c14n10", CanonicalizationMethod.INCLUSIVE);
        METHODS.put("c14n10Comments", CanonicalizationMethod.INCLUSIVE_WITH_COMMENTS);
        METHODS.put("exclusive", CanonicalizationMethod.EXCLUSIVE);
        METHODS.put("exclusiveComments", CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS);
        METHODS.put("c14n11", "http://www.w3.org/2006/12/xml-c14n11");
        METHODS.put("c14n11Comments", "http://www.w3.org/2006/12/xml-c14n11#WithComments");
    }

    /**
     * Request: {@code {"filter": "/a/b", "canonicalization": "c14n10"|"c14n10Comments"|"exclusive"|
     * "exclusiveComments"|"c14n11"|"c14n11Comments"|"none" (or the W3C identifier), "algorithm": "SHA512",
     * "targetHeader": "SAPMessageDigest", "namespaces": {...}, "baseDir": "...", "input": {...}}}.
     * Result: {@code header} (name and value), {@code canonical} (the text that was hashed, unless it is
     * the body itself) and {@code output}, the message with the header.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String base = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(base == null || base.isBlank() ? null : Path.of(base), null);
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        boolean ok = true;
        resolver.begin();
        try {
            String header = Json.str(req, "targetHeader", "SAPMessageDigest").trim();
            if (header.isEmpty()) {
                throw new IllegalArgumentException("Enter a Target Header Name (SAP: mandatory; the default is SAPMessageDigest).");
            }
            String algorithm = Json.str(req, "algorithm", "SHA512");
            String jdkAlgorithm = ALGORITHMS.get(algorithm.replace("-", "").toUpperCase(Locale.ROOT));
            if (jdkAlgorithm == null) {
                throw new IllegalArgumentException("Unknown Digest Algorithm '" + algorithm + "' (SAP: MD5, SHA1, SHA256, SHA384, SHA512).");
            }
            String method = Json.str(req, "canonicalization", "c14n10").trim();
            String filter = Json.str(req, "filter", "").trim();
            byte[] hashed;
            if ("none".equalsIgnoreCase(method)) {
                if (!filter.isEmpty()) {
                    throw new IllegalArgumentException("UnsupportedInSimulator: a filter with the canonicalization method None. "
                            + "How CPI writes the selected part without canonicalization is not known (docs/plan.md Q-E1).");
                }
                hashed = input.getBody();
                diags.add(Diagnostic.info("Canonicalization None: the digest is of the body's bytes as they are. "
                        + "That is an assumption, not verified on a tenant (docs/plan.md Q-E1).").toJson());
                try {
                    parse(hashed, resolver);
                } catch (Exception e) {
                    diags.add(Diagnostic.warning("The body is not well-formed XML. SAP: \"This step can only process XML content\"; "
                            + "what a tenant does with this body is not verified (docs/plan.md Q-E1).").toJson());
                }
            } else {
                String uri = METHODS.containsValue(method) ? method : METHODS.get(method);
                if (uri == null) {
                    throw new IllegalArgumentException("Unknown Canonicalization Method '" + method + "' (" + String.join(", ", METHODS.keySet())
                            + ", none, or the W3C identifier). The values an exported iFlow writes are not all known (docs/plan.md F62).");
                }
                Document doc;
                try {
                    doc = parse(input.getBody(), resolver);
                } catch (Exception e) {
                    throw new IllegalArgumentException("The body is not well-formed XML, so it has no canonical form (SAP: \"This step can "
                            + "only process XML content\"): " + XsltStep.message(e));
                }
                hashed = canonical(filter.isEmpty() ? doc : select(doc, filter, XsltStep.stringMap(Json.obj(req, "namespaces"))), uri);
                result.with("canonical", new String(hashed, StandardCharsets.UTF_8));
            }
            String value = Base64.getEncoder().encodeToString(MessageDigest.getInstance(jdkAlgorithm).digest(hashed));
            input.getHeaders().put(header, value);
            result.with("header", Json.o().with("name", header).with("value", value)).with("output", input.toJson());
        } catch (Exception e) {
            ok = false;
            diags.add(Diagnostic.error(XsltStep.message(e)).toJson());
        } finally {
            ProjectResolver.end();
        }
        return result.with("ok", ok).with("diagnostics", diags)
                .with("engine", "JDK " + Runtime.version().feature() + " (XML canonicalization, XPath 1.0)")
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    /** As the other DOM steps parse: entities and DTDs only through the project's resolver. */
    private static Document parse(byte[] body, ProjectResolver resolver) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver(resolver);
        builder.setErrorHandler(null);
        InputSource in = new InputSource(new ByteArrayInputStream(body));
        in.setSystemId(ProjectResolver.MEMORY_BASE + "input.xml");
        return builder.parse(in);
    }

    private static Node select(Document doc, String filter, Map<String, String> namespaces) throws Exception {
        XPath xp = XPathFactory.newDefaultInstance().newXPath();
        xp.setNamespaceContext(XPathStep.namespaceContext(namespaces));
        NodeList nodes;
        try {
            nodes = (NodeList) xp.evaluate(filter, doc, XPathConstants.NODESET);
        } catch (Exception e) {
            throw new IllegalArgumentException("The filter '" + filter + "' is not an XPath 1.0 expression that selects nodes: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
        if (nodes.getLength() != 1) {
            throw new IllegalArgumentException("UnsupportedInSimulator: the filter '" + filter + "' selects " + nodes.getLength()
                    + " nodes. What CPI hashes for several nodes or for none is not known (docs/plan.md Q-E1); select one element.");
        }
        Node node = nodes.item(0);
        if (node.getNodeType() != Node.ELEMENT_NODE && node.getNodeType() != Node.DOCUMENT_NODE) {
            throw new IllegalArgumentException("UnsupportedInSimulator: the filter '" + filter + "' selects a " + node.getNodeName()
                    + " node; select an element (docs/plan.md Q-E1).");
        }
        return node;
    }

    /**
     * The canonical form of {@code apex} and everything below it, as a document subset: namespaces (and, for
     * Canonical XML, the xml: attributes) that the element inherits from its ancestors are part of it.
     */
    static byte[] canonical(Node apex, String uri) throws Exception {
        CanonicalizationMethod method = XMLSignatureFactory.getInstance("DOM").newCanonicalizationMethod(uri, (C14NMethodParameterSpec) null);
        List<Node> nodes = new ArrayList<>();
        collect(apex, nodes);
        NodeSetData<Node> subset = nodes::iterator;
        return ((OctetStreamData) method.transform(subset, null)).getOctetStream().readAllBytes();
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            out.add(attributes.item(i));
        }
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            collect(c, out);
        }
    }
}
