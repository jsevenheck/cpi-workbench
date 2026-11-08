package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmSequenceIterator;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The converters of CPI's Message Transformers, written from SAP's documentation (plan S30: "Define JSON to XML
 * Converter", "Define XML to JSON Converter", their "Conversion Rules" and "Limitations", the examples with and
 * without namespace mapping, "How Streaming in the XML-to-JSON Converter Works", "Configure CSV to XML / XML to
 * CSV Converter" and SAP's two CSV examples). Rules SAP states are followed as stated, including the cases SAP
 * calls wrong results or invalid JSON (mixed content, duplicate members when streaming). Where SAP is silent, the
 * code says "assumption"; docs/fidelity.md lists them.
 *
 * <p>Request: {@code {"conversion": "jsonToXml"|"xmlToJson"|"xmlToCsv"|"csvToXml", ...settings, "namespaces":
 * {iFlow prefix: uri}, "baseDir": "...", "input": {"body", "headers", "properties"}}}. Result: {@code body}.
 */
public final class ConverterStep {

    /** SAP's XML declaration in the converters' output examples (single quotes). */
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String XS = "http://www.w3.org/2001/XMLSchema";

    private final Processor processor = new Processor(false);

    public ConverterStep() {
        ProjectResolver.install(processor.getUnderlyingConfiguration());
    }

    public synchronized Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String conversion = Json.str(req, "conversion", "");
        String base = Json.str(req, "baseDir", null);
        ProjectResolver resolver = new ProjectResolver(base == null || base.isBlank() ? null : Path.of(base), null);
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        Run run = new Run(req, resolver, input);
        Json.Obj result = Json.o();
        boolean ok = true;
        resolver.begin();
        try {
            switch (conversion) {
                case "jsonToXml":
                    result.with("body", run.jsonToXml());
                    break;
                case "xmlToJson":
                    String encoding = run.outputEncoding();
                    result.with("body", run.xmlToJson()).with("encoding", encoding);
                    break;
                case "xmlToCsv":
                    result.with("body", run.xmlToCsv());
                    break;
                case "csvToXml":
                    result.with("body", run.csvToXml());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown conversion '" + conversion
                            + "' (jsonToXml, xmlToJson, xmlToCsv or csvToXml).");
            }
        } catch (Exception e) {
            ok = false;
            run.diags.add(Diagnostic.error(message(e)).toJson());
        } finally {
            ProjectResolver.end();
        }
        return result.with("ok", ok).with("diagnostics", run.diags)
                .with("engine", "Converter (SAP's documented rules) · " + XsltStep.engineVersion())
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    /** Saxon 9.9 wraps the project resolver's refusal in "Exception thrown by URIResolver"; the cause says why. */
    static String message(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        for (Throwable c = e.getCause(); c != null && msg.startsWith("Exception thrown by URIResolver"); c = c.getCause()) {
            if (c.getMessage() != null) {
                msg = c.getMessage();
            }
        }
        return msg;
    }

    /** One conversion: its settings, message and diagnostics. */
    private final class Run {
        final Map<String, Object> req;
        final ProjectResolver resolver;
        final SimExchange input;
        final List<Object> diags = new ArrayList<>();
        private final Set<String> warned = new HashSet<>();

        Run(Map<String, Object> req, ProjectResolver resolver, SimExchange input) {
            this.req = req;
            this.resolver = resolver;
            this.input = input;
        }

        String str(String key, String def) {
            return Json.str(req, key, def);
        }

        boolean bool(String key) {
            return Json.bool(req, key, false);
        }

        void warnOnce(String key, String message) {
            if (warned.add(key)) {
                diags.add(Diagnostic.warning(message).toJson());
            }
        }

        void infoOnce(String key, String message) {
            if (warned.add(key)) {
                diags.add(Diagnostic.info(message).toJson());
            }
        }

        XdmNode parseBody() throws Exception {
            return processor.newDocumentBuilder().build(resolver.source(
                    new InputSource(new ByteArrayInputStream(input.getBody())), ProjectResolver.MEMORY_BASE + "input.xml"));
        }

        // ------------------------------------------------------------ JSON to XML

        String jsonToXml() {
            boolean useNs = bool("useNamespaces");
            String sep = separator();
            Map<String, String> prefixToNs = new LinkedHashMap<>();
            if (useNs) {
                for (String[] m : mapping()) {
                    prefixToNs.put(m[0], m[1]);
                }
            }
            Object doc = new JsonReader(input.getBodyAsString()).document();
            if (!(doc instanceof JObj)) {
                throw new IllegalArgumentException("JsonXmlException: the JSON text must start with an object; texts that start "
                        + "with an array or a value are not supported (SAP: Limitations for JSON to XML Conversion).");
            }
            JObj root = (JObj) doc;
            StringBuilder decls = new StringBuilder();
            prefixToNs.forEach((p, ns) -> decls.append(" xmlns:").append(p).append("=\"").append(escapeAttr(ns)).append('"'));
            JsonToXml w = new JsonToXml(useNs, sep, prefixToNs);
            // SAP's conversion rules: "This error is also raised, if you've chosen the option Add XML Root Element."
            if (!root.members.isEmpty() && root.members.get(0).name.startsWith("@")) {
                throw new IllegalArgumentException("The root member name starts with the attribute indicator @. An attribute "
                        + "cannot be root.");
            }
            if (bool("addRootElement")) {
                // The added root element wraps the JSON object, so it may have several members (an assumption: SAP's
                // tip for such texts wraps them in a Content Modifier instead).
                String name = str("rootElementName", "").trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Enter the name of the XML root element (Add XML Root Element).");
                }
                w.checkName(name, name);
                String prefix = str("rootNamespace", "").trim();
                if (!prefix.isEmpty()) {
                    String uri = Json.str(Json.obj(req, "namespaces"), prefix, null);
                    if (uri == null) {
                        throw new IllegalArgumentException("The root element's namespace prefix '" + prefix
                                + "' is not in the Namespaces tab (the iFlow's Namespace Mapping).");
                    }
                    if (!prefixToNs.containsKey(prefix)) {
                        decls.append(" xmlns:").append(prefix).append("=\"").append(escapeAttr(uri)).append('"');
                    }
                    name = prefix + ":" + name;
                }
                w.object(name, root, decls.toString());
            } else {
                if (root.members.isEmpty()) {
                    throw new IllegalArgumentException("IllegalStateException: the root JSON object is empty; it must contain "
                            + "exactly one member (SAP: Limitations for JSON to XML Conversion).");
                }
                if (root.members.size() > 1) {
                    throw new IllegalArgumentException("JsonXmlException: the root JSON object has " + root.members.size()
                            + " members; it must contain exactly one (SAP: Limitations for JSON to XML Conversion). "
                            + "Select Add XML Root Element, or wrap the text in a Content Modifier: { \"root\": ${in.body} }.");
                }
                Member m = root.members.get(0);
                if (m.value instanceof List) {
                    throw new IllegalArgumentException("UnsupportedInSimulator: the root member '" + m.name + "' is an array, "
                            + "which would give several root elements; SAP does not document this case.");
                }
                w.element(m.name, m.value, decls.toString());
            }
            for (int i = 0; i < w.out.length(); i++) {
                char c = w.out.charAt(i);
                if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == 0xFFFE || c == 0xFFFF) {
                    warnOnce("char", String.format("The JSON holds the character U+%04X, which XML 1.0 does not allow: the "
                            + "result is not well-formed.", (int) c));
                    break;
                }
            }
            return XML_DECLARATION + w.out;
        }

        /** Writes the XML text; names follow SAP's rules for prefixes, separators and NCNames. */
        private final class JsonToXml {
            final boolean useNs;
            final String sep;
            final Map<String, String> prefixToNs;
            final StringBuilder out = new StringBuilder();

            JsonToXml(boolean useNs, String sep, Map<String, String> prefixToNs) {
                this.useNs = useNs;
                this.sep = sep;
                this.prefixToNs = prefixToNs;
            }

            void element(String jsonName, Object value, String decls) {
                if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        if (item instanceof List) {
                            throw new IllegalArgumentException("UnsupportedInSimulator: '" + jsonName + "' holds an array "
                                    + "inside an array; SAP documents no XML form for it.");
                        }
                        element(jsonName, item, decls);
                    }
                    return;
                }
                String name = xmlName(jsonName);
                if (value instanceof JObj) {
                    object(name, (JObj) value, decls);
                    return;
                }
                String text = scalar(value);
                out.append('<').append(name).append(decls);
                if (text.isEmpty()) {
                    out.append("/>"); // SAP: "C": null and "D": "" give an empty element
                } else {
                    out.append('>').append(escapeText(text)).append("</").append(name).append('>');
                }
            }

            void object(String name, JObj obj, String decls) {
                out.append('<').append(name).append(decls);
                boolean childSeen = false;
                List<Member> content = new ArrayList<>();
                for (Member m : obj.members) {
                    if (m.name.startsWith("@")) {
                        if (childSeen) {
                            throw new IllegalArgumentException("Trying to write an attribute when there is no open start "
                                    + "element (SAP: members starting with @ must come first; here '" + m.name + "' in '"
                                    + name + "').");
                        }
                        if (m.value == null) {
                            throw new IllegalArgumentException("JsonXmlException: \"" + m.name + "\": null cannot be converted "
                                    + "to an XML attribute (SAP: Limitations for JSON to XML Conversion).");
                        }
                        if (m.value instanceof JObj || m.value instanceof List) {
                            throw new IllegalArgumentException("JsonXmlException: the attribute member '" + m.name
                                    + "' must have a string, number or boolean value.");
                        }
                        out.append(' ').append(xmlName(m.name.substring(1))).append("=\"")
                                .append(escapeAttr(scalar(m.value))).append('"');
                    } else {
                        childSeen = true;
                        content.add(m);
                    }
                }
                if (content.isEmpty()) {
                    out.append("/>");
                    return;
                }
                out.append('>');
                boolean text = false;
                boolean elements = false;
                for (Member m : content) {
                    if ("$".equals(m.name)) {
                        if (m.value instanceof JObj || m.value instanceof List) {
                            throw new IllegalArgumentException("JsonXmlException: the value member \"$\" of '" + name
                                    + "' must be a string, number or boolean.");
                        }
                        text = true;
                        out.append(escapeText(scalar(m.value)));
                    } else {
                        elements = true;
                        element(m.name, m.value, "");
                    }
                }
                if (text && elements) {
                    warnOnce("mixed", "'" + name + "' has a \"$\" value and child elements: mixed content, which SAP does "
                            + "not support (Limitations for JSON to XML Conversion).");
                }
                out.append("</").append(name).append('>');
            }

            /** SAP: prefix + separator + local name when the prefix is mapped; else the full member name. */
            String xmlName(String jsonName) {
                if (useNs) {
                    int i = jsonName.indexOf(sep);
                    if (i > 0) {
                        String prefix = jsonName.substring(0, i);
                        String local = jsonName.substring(i + sep.length());
                        if (prefixToNs.containsKey(prefix)) {
                            checkName(local, jsonName);
                            return prefix + ":" + local;
                        }
                        if (!":".equals(sep)) {
                            checkName(jsonName, jsonName);
                            return jsonName;
                        }
                        checkPrefixed(jsonName);
                        warnOnce("prefix:" + prefix, "The JSON prefix '" + prefix + "' has no XML namespace, so '" + jsonName
                                + "' is written as the element name, as SAP documents; the result is not namespace-well-formed.");
                        return jsonName;
                    }
                }
                if (!useNs && (jsonName.indexOf('.') > 0 || jsonName.indexOf(':') > 0)) {
                    infoOnce("noNsPrefix", "'" + jsonName + "' keeps its full name without namespace mapping, as SAP's rule "
                            + "says (\"p.A\" leads to <p.A>); SAP's example without namespace mapping drops the prefix "
                            + "(\"abc.A\" to <A>) instead. Which one CPI does is to verify.");
                }
                if (jsonName.indexOf(':') > 0) {
                    checkPrefixed(jsonName);
                    warnOnce("colon:" + jsonName, "'" + jsonName + "' is written as it is; without a namespace for its prefix "
                            + "the result is not namespace-well-formed (SAP: Limitations for JSON to XML Conversion).");
                    return jsonName;
                }
                checkName(jsonName, jsonName);
                return jsonName;
            }

            /** A name with one colon, written as it is: the prefix and the local name must each be valid names. */
            void checkPrefixed(String jsonName) {
                int i = jsonName.indexOf(':');
                if (jsonName.indexOf(':', i + 1) >= 0) {
                    checkName("", jsonName);
                }
                checkName(jsonName.substring(0, i), jsonName);
                checkName(jsonName.substring(i + 1), jsonName);
            }

            /** SAP's naming rules: start with a letter or underscore, then letters, digits, - _ .; not "xml...". */
            void checkName(String name, String member) {
                boolean ok = !name.isEmpty() && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')
                        && !name.toLowerCase().startsWith("xml");
                for (int i = 1; ok && i < name.length(); i++) {
                    char c = name.charAt(i);
                    ok = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
                }
                if (!ok) {
                    throw new IllegalArgumentException("JsonXmlException: the JSON member name '" + member + "' cannot be "
                            + "converted into a valid XML name (SAP: names start with a letter or underscore, not with "
                            + "'xml', and contain letters, digits, hyphens, underscores and periods).");
                }
            }
        }

        // ------------------------------------------------------------ XML to JSON

        String xmlToJson() throws Exception {
            XdmNode root = rootElement(parseBody());
            XmlToJson w = new XmlToJson();
            String path = "/" + qname(root);
            if (bool("suppressRoot")) {
                w.value(root, path);
            } else {
                w.out.append('{');
                w.member(w.jsonName(root), List.of(root), path, w.isArray(path));
                w.out.append('}');
            }
            return w.out.toString();
        }

        /** SAP: from the header or property CamelCharsetName, else UTF-8, unless the step names an encoding. */
        String outputEncoding() {
            String enc = str("outputEncoding", "").trim();
            if (enc.isEmpty() || enc.equalsIgnoreCase("from header or property")) {
                Object v = input.getHeaders().get("CamelCharsetName");
                if (v == null) {
                    v = input.getProperties().get("CamelCharsetName");
                }
                enc = v == null ? "UTF-8" : v.toString();
            }
            if (!Charset.isSupported(enc)) {
                throw new IllegalArgumentException("Unknown JSON output encoding '" + enc + "'.");
            }
            return enc;
        }

        private final class XmlToJson {
            final boolean useNs = bool("useNamespaces");
            final boolean streaming = bool("streaming");
            final boolean all = !"specific".equals(str("streamingElements", "all"));
            final String sep = separator();
            final Map<String, String> nsToPrefix = new LinkedHashMap<>();
            final Set<String> arrayPaths = new HashSet<>();
            final StringBuilder out = new StringBuilder();

            XmlToJson() {
                if (useNs) {
                    for (String[] m : mapping()) {
                        nsToPrefix.put(m[1], m[0]);
                    }
                }
                for (String p : lines(req.get("arrayPaths"))) {
                    arrayPaths.add(p.startsWith("/") ? p : "/" + p);
                }
            }

            boolean isArray(String path) {
                return streaming && (all || arrayPaths.contains(path));
            }

            /** One member: one value, or an array of the run's values. */
            void member(String name, List<XdmNode> run, String path, boolean array) {
                out.append(quote(name)).append(':');
                if (array || run.size() > 1) {
                    out.append('[');
                    for (int i = 0; i < run.size(); i++) {
                        if (i > 0) {
                            out.append(',');
                        }
                        value(run.get(i), path);
                    }
                    out.append(']');
                } else {
                    value(run.get(0), path);
                }
            }

            /**
             * SAP: an element without attributes and children is a string ("" when empty; whitespace is kept);
             * attributes are "@" members, then the children, then "$" for the text. Non-streaming groups all
             * siblings of a name into one array (the order of other siblings is lost); streaming writes the
             * siblings in document order, an array only for configured elements, so a repeated name that is no
             * array becomes a duplicate member (invalid JSON, as SAP documents).
             */
            void value(XdmNode el, String path) {
                List<XdmNode> attrs = children(el, Axis.ATTRIBUTE, null);
                List<XdmNode> elements = children(el, Axis.CHILD, XdmNodeKind.ELEMENT);
                // Text of a simple element is kept as it is (SAP: " " stays " "); next to child elements only text
                // nodes that are not whitespace count (SAP ignores the line breaks and spaces between elements).
                StringBuilder text = new StringBuilder();
                for (XdmNode t : children(el, Axis.CHILD, XdmNodeKind.TEXT)) {
                    if (elements.isEmpty() || !t.getStringValue().isBlank()) {
                        text.append(t.getStringValue());
                    }
                }
                if (attrs.isEmpty() && elements.isEmpty()) {
                    out.append(quote(text.toString()));
                    return;
                }
                out.append('{');
                boolean first = true;
                for (XdmNode a : attrs) {
                    first = comma(first);
                    out.append(quote("@" + jsonName(a))).append(':').append(quote(a.getStringValue()));
                }
                if (!streaming) {
                    Map<String, List<XdmNode>> groups = new LinkedHashMap<>();
                    Map<String, String> paths = new LinkedHashMap<>();
                    for (XdmNode c : elements) {
                        String n = jsonName(c);
                        groups.computeIfAbsent(n, k -> new ArrayList<>()).add(c);
                        paths.putIfAbsent(n, path + "/" + qname(c));
                    }
                    for (Map.Entry<String, List<XdmNode>> g : groups.entrySet()) {
                        first = comma(first);
                        member(g.getKey(), g.getValue(), paths.get(g.getKey()), false);
                    }
                } else {
                    Set<String> written = new HashSet<>();
                    for (int i = 0; i < elements.size(); ) {
                        XdmNode c = elements.get(i);
                        String n = jsonName(c);
                        String p = path + "/" + qname(c);
                        boolean array = isArray(p);
                        List<XdmNode> run = new ArrayList<>();
                        run.add(c);
                        i++;
                        while (array && i < elements.size() && jsonName(elements.get(i)).equals(n)) {
                            run.add(elements.get(i++));
                        }
                        if (!written.add(n)) {
                            warnOnce("dup:" + p, array
                                    ? "'" + n + "' appears again after other elements: streaming writes each run of "
                                    + "neighbouring elements as an array of its own, so the JSON has a duplicate member and "
                                    + "is not valid (an assumption from SAP's streaming rules)."
                                    : "'" + n + "' appears more than once in its parent without being an array: the JSON has "
                                    + "a duplicate member and is not valid, as SAP documents for streaming. List " + p
                                    + " under the array elements, or choose All.");
                        }
                        first = comma(first);
                        member(n, run, p, array);
                    }
                }
                if (text.length() > 0) {
                    if (!elements.isEmpty()) {
                        warnOnce("mixed", "'" + qname(el) + "' has text and child elements: mixed content, which SAP does not "
                                + "support; the result is SAP's documented wrong result (the text joined in \"$\").");
                    }
                    first = comma(first);
                    out.append("\"$\":").append(quote(text.toString()));
                }
                out.append('}');
            }

            boolean comma(boolean first) {
                if (!first) {
                    out.append(',');
                }
                return false;
            }

            /**
             * SAP: prefix + separator + local name for a mapped namespace; the XML prefix when the namespace is not
             * mapped; an error for an unmapped namespace without prefix (streaming ignores it). Without namespace
             * mapping the local name (SAP's example).
             */
            String jsonName(XdmNode node) {
                QName q = node.getNodeName();
                String uri = q.getNamespaceURI();
                if (!useNs || uri.isEmpty()) {
                    return q.getLocalName();
                }
                String prefix = nsToPrefix.get(uri);
                if (prefix != null) {
                    return prefix + sep + q.getLocalName();
                }
                if (!q.getPrefix().isEmpty()) {
                    return q.getPrefix() + sep + q.getLocalName();
                }
                if (streaming) {
                    return q.getLocalName();
                }
                throw new IllegalArgumentException("IllegalStateException Invalid JSON namespace: " + uri + " (SAP: an element "
                        + "with a namespace but no prefix needs that namespace in the namespace mapping)");
            }
        }

        // ------------------------------------------------------------ XML to CSV

        String xmlToCsv() throws Exception {
            String sourcePath = str("sourcePath", "").trim();
            if (sourcePath.isEmpty()) {
                throw new IllegalArgumentException("Enter the Path to Source Element in XML (e.g. /Products/Product).");
            }
            String header = str("csvHeader", "none");
            if ("xsd".equals(header)) {
                throw new IllegalArgumentException("UnsupportedInSimulator: CSV headers from XSD annotations are not simulated; "
                        + "choose None or XML based.");
            }
            String sep = fieldSeparator();
            boolean attributes = bool("includeAttributes");
            XdmNode doc = parseBody();
            List<String> lines = new ArrayList<>();
            if (bool("includeParent")) {
                String parentPath = str("parentPath", "").trim();
                if (parentPath.isEmpty()) {
                    throw new IllegalArgumentException("Enter the Path to Parent Element (Include Parent Element).");
                }
                List<XdmNode> parents = select(doc, parentPath);
                if (parents.isEmpty()) {
                    throw new IllegalArgumentException("The Path to Parent Element '" + parentPath + "' selects no element.");
                }
                List<String[]> fields = fields(parents.get(0), attributes, true, sep);
                if ("xml".equals(header)) {
                    lines.add(join(fields, 0, sep));
                }
                lines.add(join(fields, 1, sep));
            }
            List<XdmNode> records = select(doc, sourcePath);
            if (records.isEmpty()) {
                diags.add(Diagnostic.info("The Path to Source Element '" + sourcePath + "' selects no element.").toJson());
            }
            List<String> names = null;
            for (XdmNode r : records) {
                List<String[]> fields = fields(r, attributes, false, sep);
                List<String> these = new ArrayList<>();
                fields.forEach(f -> these.add(f[0]));
                if (names == null) {
                    names = these;
                    if ("xml".equals(header)) {
                        lines.add(join(fields, 0, sep));
                    }
                } else if (!names.equals(these)) {
                    warnOnce("fields", "The records have different fields; the header comes from the first record and the "
                            + "values are written in the order they occur (SAP).");
                }
                lines.add(join(fields, 1, sep));
            }
            if (names != null && names.isEmpty()) {
                diags.add(Diagnostic.info("The source elements have no simple child elements"
                        + (attributes ? " or attributes" : "; select Include Attribute Values to write their attributes")
                        + ", so their rows are empty.").toJson());
            }
            // An assumption: lines end with \n and the text has no trailing line break (SAP's example ends there).
            return String.join("\n", lines);
        }

        /** Attributes (when included), then the simple child elements: {name, value} in document order. */
        private List<String[]> fields(XdmNode el, boolean attributes, boolean skipComplex, String sep) {
            List<String[]> out = new ArrayList<>();
            if (attributes) {
                for (XdmNode a : children(el, Axis.ATTRIBUTE, null)) {
                    out.add(new String[] {a.getNodeName().getLocalName(), a.getStringValue()});
                }
            }
            for (XdmNode c : children(el, Axis.CHILD, XdmNodeKind.ELEMENT)) {
                if (!children(c, Axis.CHILD, XdmNodeKind.ELEMENT).isEmpty()) {
                    if (skipComplex) {
                        continue;
                    }
                    throw new IllegalArgumentException("UnsupportedInSimulator: '" + qname(c) + "' in '" + qname(el) + "' has "
                            + "child elements; SAP supports only simple types in the source of the XML to CSV converter.");
                }
                String v = c.getStringValue();
                if (v.contains("\n") || v.contains("\r")) {
                    warnOnce("newline", "A value contains a line break, which SAP's XML to CSV converter does not support.");
                }
                if (v.contains(sep)) {
                    warnOnce("sep", "A value contains the field separator; it is written as it is (an assumption: no quoting).");
                }
                out.add(new String[] {c.getNodeName().getLocalName(), v});
            }
            return out;
        }

        private String join(List<String[]> fields, int part, String sep) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(fields.get(i)[part]);
            }
            return sb.toString();
        }

        private List<XdmNode> select(XdmNode doc, String path) throws Exception {
            XPathCompiler xc = processor.newXPathCompiler();
            xc.setBaseURI(URI.create(resolver.resolveAgainst(".", null)));
            XsltStep.stringMap(Json.obj(req, "namespaces")).forEach(xc::declareNamespace);
            // SAP's examples write the path without the leading slash (root/Products/Product); it starts at the root.
            String expr = path.startsWith("/") ? path : "/" + path;
            // The same guards as XPathStep: no fn:transform, and doc() etc. read inside the project only.
            XPathSelector sel = XPathStep.compile(xc, expr, input);
            sel.setURIResolver(resolver);
            sel.setContextItem(doc);
            List<XdmNode> out = new ArrayList<>();
            for (XdmItem item : sel.evaluate()) {
                if (item instanceof XdmNode && ((XdmNode) item).getNodeKind() == XdmNodeKind.ELEMENT) {
                    out.add((XdmNode) item);
                }
            }
            return out;
        }

        // ------------------------------------------------------------ CSV to XML

        String csvToXml() throws Exception {
            String schema = str("schema", "").trim();
            if (schema.startsWith("pd:")) {
                throw new IllegalArgumentException("UnsupportedInSimulator: an XML schema from the Partner Directory ("
                        + schema + "); give a project file.");
            }
            if (schema.isEmpty()) {
                throw new IllegalArgumentException("Choose the XML Schema (a .xsd file of the project).");
            }
            String target = str("targetPath", "").trim().replaceAll("^/+", "");
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Enter the Path to Target Element in XSD (e.g. Products/Product).");
            }
            Xsd xsd = new Xsd(schema);
            List<String> path = List.of(target.split("/"));
            List<XdmNode> pathDecls = xsd.pathDecls(path);
            List<XdmNode> fieldDecls = xsd.recordFields(pathDecls);
            List<String> fields = new ArrayList<>();
            fieldDecls.forEach(d -> fields.add(xsd.declName(d)));
            String sep = fieldSeparator();
            String marker = str("recordMarker", "");
            boolean headerLine = bool("firstLineHeader");
            boolean byName = headerLine && !"csv".equals(str("headerMapping", "xsd"));
            List<List<String>> rows = csvRows(input.getBodyAsString(), sep);
            List<String> header = headerLine && !rows.isEmpty() ? rows.remove(0) : List.of();
            if (byName) {
                List<String> unknown = new ArrayList<>(fields);
                unknown.removeAll(header);
                if (!unknown.isEmpty()) {
                    diags.add(Diagnostic.info("No CSV column for " + String.join(", ", unknown)
                            + "; these elements are left out.").toJson());
                }
            }
            String p = xsd.prefix();
            StringBuilder out = new StringBuilder(XML_DECLARATION);
            // The root and the elements down to the record's parent are written once (SAP's example: one Products).
            for (int i = 0; i < path.size() - 1; i++) {
                out.append('<').append(xsd.name(pathDecls.get(i)));
                if (i == 0 && p != null) {
                    out.append(" xmlns:").append(p).append("=\"").append(escapeAttr(xsd.targetNamespace)).append('"');
                }
                out.append('>');
            }
            int count = 0;
            for (List<String> row : rows) {
                if (!marker.isEmpty() && (row.isEmpty() || !marker.equals(row.get(0)))) {
                    continue;
                }
                count++;
                String record = xsd.name(pathDecls.get(path.size() - 1));
                out.append('<').append(record);
                if (path.size() == 1 && count == 1 && p != null) {
                    out.append(" xmlns:").append(p).append("=\"").append(escapeAttr(xsd.targetNamespace)).append('"');
                }
                out.append('>');
                // Without header names the values go to the XSD elements in order; a marker column is not mapped (SAP).
                int offset = !byName && !marker.isEmpty() ? 1 : 0;
                for (int f = 0; f < fields.size(); f++) {
                    int col = byName ? header.indexOf(fields.get(f)) : offset + f;
                    if (col < 0 || col >= row.size()) {
                        continue; // an assumption: a missing value leaves the element out
                    }
                    String n = xsd.name(fieldDecls.get(f));
                    String v = row.get(col);
                    out.append('<').append(n);
                    out.append(v.isEmpty() ? "/>" : ">" + escapeText(v) + "</" + n + ">");
                }
                out.append("</").append(record).append('>');
                if (path.size() == 1 && count == 1 && rows.size() > 1) {
                    warnOnce("roots", "The target path names only the root element, so each row would be a document of "
                            + "its own; give the path down to the record element (e.g. Products/Product).");
                    break;
                }
            }
            for (int i = path.size() - 2; i >= 0; i--) {
                out.append("</").append(xsd.name(pathDecls.get(i))).append('>');
            }
            if (!marker.isEmpty() && count == 0) {
                diags.add(Diagnostic.info("No row starts with the record marker '" + marker + "'.").toJson());
            }
            return out.toString();
        }

        /**
         * CSV rows. An assumption: fields may be quoted with double quotes (RFC 4180, "" for a quote inside), and
         * empty lines are skipped; SAP does not document either.
         */
        private List<List<String>> csvRows(String text, String sep) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"' && field.length() == 0) {
                    quoted = true;
                    any = true;
                } else if (text.startsWith(sep, i)) {
                    row.add(field.toString());
                    field.setLength(0);
                    i += sep.length() - 1;
                    any = true;
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (any || field.length() > 0) {
                        row.add(field.toString());
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                    field.setLength(0);
                    any = false;
                } else {
                    field.append(c);
                    any = true;
                }
            }
            if (quoted) {
                throw new IllegalArgumentException("A quoted CSV field is not closed.");
            }
            if (any || field.length() > 0) {
                row.add(field.toString());
                rows.add(row);
            }
            return rows;
        }

        /** The parts of an XSD the CSV to XML converter needs: element declarations and their content order. */
        private final class Xsd {
            final XdmNode schema;
            final String targetNamespace;
            final boolean qualified;

            Xsd(String file) throws Exception {
                String systemId = resolver.systemIdFor(file);
                XdmNode doc = processor.newDocumentBuilder().build(resolver.source(new InputSource(
                        new StringReader(resolver.load(systemId))), systemId));
                schema = rootElement(doc);
                if (!XS.equals(schema.getNodeName().getNamespaceURI()) || !"schema".equals(schema.getNodeName().getLocalName())) {
                    throw new IllegalArgumentException(file + " is not an XML schema.");
                }
                String tns = schema.getAttributeValue(new QName("targetNamespace"));
                targetNamespace = tns == null || tns.isEmpty() ? null : tns;
                qualified = "qualified".equals(schema.getAttributeValue(new QName("elementFormDefault")));
            }

            /** An assumption: with a target namespace the elements get the prefix ns0 (SAP does not document it). */
            String prefix() {
                return targetNamespace == null ? null : "ns0";
            }

            /**
             * The element's name in the output: with the prefix when it is in the target namespace. XSD rules:
             * global declarations (also through ref) are qualified; local ones by their form attribute, else by
             * elementFormDefault.
             */
            String name(XdmNode decl) {
                String local = declName(decl);
                return prefix() != null && qualified(decl) ? prefix() + ":" + local : local;
            }

            private boolean qualified(XdmNode decl) {
                if (decl.getAttributeValue(new QName("ref")) != null || XS.equals(decl.getParent().getNodeName().getNamespaceURI())
                        && "schema".equals(decl.getParent().getNodeName().getLocalName())) {
                    return true;
                }
                String form = decl.getAttributeValue(new QName("form"));
                return form != null ? "qualified".equals(form) : qualified;
            }

            /** The declarations of the target path's elements, from the global root element down. */
            List<XdmNode> pathDecls(List<String> path) {
                List<XdmNode> decls = new ArrayList<>();
                XdmNode decl = global("element", path.get(0));
                if (decl == null) {
                    throw new IllegalArgumentException("The XSD has no global element '" + path.get(0) + "' (Path to Target "
                            + "Element in XSD).");
                }
                for (int i = 1; i < path.size(); i++) {
                    XdmNode next = null;
                    for (XdmNode c : contentElements(decl)) {
                        if (path.get(i).equals(declName(c))) {
                            next = c;
                        }
                    }
                    if (next == null) {
                        throw new IllegalArgumentException("'" + path.get(i) + "' is not a child element of '" + path.get(i - 1)
                                + "' in the XSD.");
                    }
                    decls.add(decl);
                    decl = next;
                }
                decls.add(decl);
                return decls;
            }

            /** The record's field declarations, in the XSD's order. */
            List<XdmNode> recordFields(List<XdmNode> pathDecls) {
                XdmNode record = pathDecls.get(pathDecls.size() - 1);
                List<XdmNode> fields = contentElements(record);
                if (fields.isEmpty()) {
                    throw new IllegalArgumentException("'" + declName(record) + "' has no child elements in the XSD.");
                }
                return fields;
            }

            String declName(XdmNode el) {
                String n = el.getAttributeValue(new QName("name"));
                return n != null ? n : local(el.getAttributeValue(new QName("ref")));
            }

            /** The element declarations of an element's complex type (sequence, all, choice), in order. */
            private List<XdmNode> contentElements(XdmNode decl) {
                String ref = decl.getAttributeValue(new QName("ref"));
                if (ref != null) {
                    decl = global("element", local(ref));
                    if (decl == null) {
                        throw new IllegalArgumentException("The XSD element reference '" + ref + "' is not declared.");
                    }
                }
                XdmNode type = xs(decl, "complexType");
                String typeName = decl.getAttributeValue(new QName("type"));
                if (type == null && typeName != null) {
                    type = global("complexType", local(typeName));
                }
                List<XdmNode> out = new ArrayList<>();
                if (type != null) {
                    collect(type, out);
                }
                return out;
            }

            private void collect(XdmNode node, List<XdmNode> out) {
                for (XdmNode c : children(node, Axis.CHILD, XdmNodeKind.ELEMENT)) {
                    if (!XS.equals(c.getNodeName().getNamespaceURI())) {
                        continue;
                    }
                    switch (c.getNodeName().getLocalName()) {
                        case "element":
                            out.add(c);
                            break;
                        case "sequence":
                        case "all":
                        case "choice":
                            collect(c, out);
                            break;
                        case "complexContent":
                        case "simpleContent":
                        case "group":
                            throw new IllegalArgumentException("UnsupportedInSimulator: xs:" + c.getNodeName().getLocalName()
                                    + " in the XSD; the simulator reads elements in xs:sequence, xs:all and xs:choice.");
                        default:
                            break;
                    }
                }
            }

            private XdmNode global(String kind, String name) {
                for (XdmNode c : children(schema, Axis.CHILD, XdmNodeKind.ELEMENT)) {
                    if (XS.equals(c.getNodeName().getNamespaceURI()) && kind.equals(c.getNodeName().getLocalName())
                            && name.equals(c.getAttributeValue(new QName("name")))) {
                        return c;
                    }
                }
                return null;
            }

            private XdmNode xs(XdmNode parent, String local) {
                for (XdmNode c : children(parent, Axis.CHILD, XdmNodeKind.ELEMENT)) {
                    if (XS.equals(c.getNodeName().getNamespaceURI()) && local.equals(c.getNodeName().getLocalName())) {
                        return c;
                    }
                }
                return null;
            }

            private String local(String qname) {
                return qname == null ? null : qname.substring(qname.indexOf(':') + 1);
            }
        }

        // ------------------------------------------------------------ settings

        /** SAP's JSON prefix separators: colon, comma, dot, pipe, semicolon and space. */
        String separator() {
            String s = str("separator", ":");
            if (s.isEmpty()) {
                return ":";
            }
            if (s.length() != 1 || ":,.|; ".indexOf(s.charAt(0)) < 0) {
                throw new IllegalArgumentException("JSON Prefix Separator '" + s + "': SAP allows colon, comma, dot, pipe, "
                        + "semicolon and space.");
            }
            return s;
        }

        /** The CSV field separator: the character itself, \t for a tab, or the name of a common one. */
        String fieldSeparator() {
            String s = str("fieldSeparator", ",");
            switch (s.toLowerCase()) {
                case "":
                case "comma":
                    return ",";
                case "semicolon":
                    return ";";
                case "pipe":
                    return "|";
                case "tab":
                case "\\t":
                    return "\t";
                case "space":
                    return " ";
                default:
                    return s;
            }
        }

        /** {prefix, namespace} pairs: a list of {"prefix", "namespace"} or lines "prefix = namespace". */
        @SuppressWarnings("unchecked")
        List<String[]> mapping() {
            List<String[]> out = new ArrayList<>();
            Object m = req.get("namespaceMapping");
            if (m instanceof List) {
                for (Object o : (List<Object>) m) {
                    if (o instanceof Map) {
                        String p = Json.str((Map<String, Object>) o, "prefix", "").trim();
                        String ns = Json.str((Map<String, Object>) o, "namespace", "").trim();
                        if (!p.isEmpty() && !ns.isEmpty()) {
                            out.add(new String[] {p, ns});
                        }
                    }
                }
            } else {
                for (String line : lines(m)) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("Namespace mapping '" + line + "': write prefix = namespace.");
                    }
                    out.add(new String[] {line.substring(0, eq).trim(), line.substring(eq + 1).trim()});
                }
            }
            for (String[] pm : out) {
                if (!pm[0].matches("[A-Za-z][A-Za-z0-9]*")) {
                    throw new IllegalArgumentException("JSON prefix '" + pm[0] + "': SAP requires a letter, then letters and "
                            + "digits.");
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    static List<String> lines(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
        } else if (v != null) {
            for (String l : v.toString().split("\\R")) {
                if (!l.isBlank()) {
                    out.add(l.trim());
                }
            }
        }
        return out;
    }

    static XdmNode rootElement(XdmNode doc) {
        for (XdmNode c : children(doc, Axis.CHILD, XdmNodeKind.ELEMENT)) {
            return c;
        }
        throw new IllegalArgumentException("The body has no root element.");
    }

    static List<XdmNode> children(XdmNode node, Axis axis, XdmNodeKind kind) {
        List<XdmNode> out = new ArrayList<>();
        XdmSequenceIterator it = node.axisIterator(axis);
        while (it.hasNext()) {
            XdmNode n = (XdmNode) it.next();
            if (kind == null || n.getNodeKind() == kind) {
                out.add(n);
            }
        }
        return out;
    }

    /** The element's name as written in the document (prefix:local), for array paths and messages. */
    static String qname(XdmNode node) {
        QName q = node.getNodeName();
        return q.getPrefix().isEmpty() ? q.getLocalName() : q.getPrefix() + ":" + q.getLocalName();
    }

    static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeAttr(String s) {
        return escapeText(s).replace("\"", "&quot;").replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;");
    }

    /** A JSON string; non-ASCII characters and / stay as they are (an assumption). */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** A JSON scalar as XML text: strings as they are, numbers as written, true/false; null is empty. */
    static String scalar(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof JNum) {
            return ((JNum) v).raw;
        }
        return v.toString();
    }

    // ---------------------------------------------------------------- JSON reader

    /** A JSON member; duplicates and their order are kept. */
    static final class Member {
        final String name;
        final Object value;

        Member(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }

    static final class JObj {
        final List<Member> members = new ArrayList<>();
    }

    /** A number as written, so 1.50 stays 1.50. */
    static final class JNum {
        final String raw;

        JNum(String raw) {
            this.raw = raw;
        }
    }

    /** A strict JSON reader (RFC 8259) that keeps member order, duplicates and number literals. */
    static final class JsonReader {
        private final String s;
        private int i;

        JsonReader(String s) {
            this.s = s;
        }

        Object document() {
            ws();
            if (i < s.length() && s.charAt(i) == '﻿') {
                i++;
                ws();
            }
            Object v = value();
            ws();
            if (i < s.length()) {
                throw error("unexpected text after the JSON value");
            }
            return v;
        }

        private Object value() {
            if (i >= s.length()) {
                throw error("unexpected end of the text");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private JObj object() {
            JObj o = new JObj();
            i++;
            ws();
            if (peek('}')) {
                i++;
                return o;
            }
            while (true) {
                ws();
                if (!peek('"')) {
                    throw error("expected a member name in double quotes");
                }
                String name = string();
                ws();
                expect(':');
                ws();
                o.members.add(new Member(name, value()));
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return o;
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) {
                i++;
                return a;
            }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return a;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw error("incomplete \\u escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("invalid \\u escape");
                            }
                            i += 4;
                            break;
                        default:
                            throw error("invalid escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("control character in a string");
                } else {
                    sb.append(c);
                }
            }
            throw error("the string is not closed");
        }

        private JNum number() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            if (peek('0') && i + 1 < s.length() && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '9') {
                throw error("a number must not start with 0");
            }
            digits();
            if (peek('.')) {
                i++;
                digits();
            }
            if (peek('e') || peek('E')) {
                i++;
                if (peek('+') || peek('-')) {
                    i++;
                }
                digits();
            }
            return new JNum(s.substring(start, i));
        }

        private void digits() {
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (start == i) {
                throw error("expected a digit");
            }
        }

        private Object word(String w, Object v) {
            if (!s.startsWith(w, i)) {
                throw error("unexpected text");
            }
            i += w.length();
            return v;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        private void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
                i++;
            }
        }

        private IllegalArgumentException error(String what) {
            int line = 1;
            int col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new IllegalArgumentException("The body is not valid JSON: " + what + " (line " + line + ", column " + col + ").");
        }
    }
}
