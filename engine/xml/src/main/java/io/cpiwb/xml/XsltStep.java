package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TypedValue;
import io.cpiwb.xml.compat.SaxonCompat;
import net.sf.saxon.jaxp.TemplatesImpl;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.trans.XPathException;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.SourceLocator;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Simulates the Cloud Integration <em>XSLT Mapping</em> step.
 *
 * <p>Behaviour reproduced (see PLAN.md, findings F10/F11):
 * <ul>
 *   <li>Every global {@code xsl:param} receives the value of the header or exchange property with the
 *       same name. Values are passed as Java objects through JAXP, as Camel's XSLT component does, so
 *       Saxon performs the Java-to-XDM conversion itself.</li>
 *   <li>A parameter named {@code exchange} receives the exchange; {@code setHeader($exchange, n, v)} and
 *       {@code setProperty($exchange, n, v)} in namespace {@code http://sap.com/it/} change the
 *       message.</li>
 *   <li>The serialized result becomes the new body.</li>
 * </ul>
 * Precedence when a header and a property share a name follows Camel's XsltBuilder (properties are
 * set first, headers override); this is configurable until verified on a tenant (Q-X2).
 */
public final class XsltStep {

    private final net.sf.saxon.TransformerFactoryImpl factory;
    private final JavaExtensions javaExtensions;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 32;
        }
    };

    private static final class CacheEntry {
        final Templates templates;
        final Map<Path, Long> files;
        final List<Diagnostic> warnings;

        CacheEntry(Templates templates, Map<Path, Long> files, List<Diagnostic> warnings) {
            this.templates = templates;
            this.files = files;
            this.warnings = warnings;
        }

        boolean upToDate() {
            for (Map.Entry<Path, Long> e : files.entrySet()) {
                try {
                    if (Files.getLastModifiedTime(e.getKey()).toMillis() != e.getValue()) {
                        return false;
                    }
                } catch (java.io.IOException ex) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Handle passed to the stylesheet as {@code $exchange}. */
    public static final class ExchangeHandle {
        final SimExchange exchange;
        final List<Object> calls = new ArrayList<>();

        ExchangeHandle(SimExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public String toString() {
            return "Exchange";
        }
    }

    public XsltStep() {
        factory = new net.sf.saxon.TransformerFactoryImpl();
        SaxonCompat.registerCpiFunctions(factory.getConfiguration(), (function, target, name, value) -> {
            if (!(target instanceof ExchangeHandle)) {
                throw new IllegalArgumentException(function + ": the first argument must be $exchange "
                        + "(declare <xsl:param name=\"exchange\"/> in the stylesheet)");
            }
            ExchangeHandle h = (ExchangeHandle) target;
            if ("setHeader".equals(function)) {
                h.exchange.getHeaders().put(name, value);
            } else {
                h.exchange.getProperties().put(name, value);
            }
            h.calls.add(Json.o().with("function", function).with("name", name).with("value", value));
        });
        javaExtensions = new JavaExtensions((ns, local, invoker) ->
                SaxonCompat.registerJavaFunction(factory.getConfiguration(), ns, local, invoker));
    }

    public static String engineVersion() {
        return net.sf.saxon.Version.getProductTitle();
    }

    /**
     * Request:
     * <pre>
     * {"xslt": {"content": "...", "path": "mappings/m.xsl"},
     *  "baseDir": "/abs/project", "resources": {"mappings/common.xsl": "..."},
     *  "input": {"body": "...", "headers": [...], "properties": [...]},
     *  "options": {"paramPrecedence": "header"|"property", "missingParameter": "default"|"error"}}
     * </pre>
     */
    public synchronized Map<String, Object> run(Map<String, Object> req) {
        List<Diagnostic> diags = new ArrayList<>();
        List<XslMessage> messages = new ArrayList<>();
        Json.Obj result = Json.o();
        Map<String, Object> xslt = Json.obj(req, "xslt");
        if (xslt == null) {
            throw new IllegalArgumentException("Missing 'xslt'");
        }
        String baseDirStr = Json.str(req, "baseDir", null);
        Path baseDir = baseDirStr == null || baseDirStr.isBlank() ? null : Path.of(baseDirStr);
        Map<String, String> resources = stringMap(Json.obj(req, "resources"));
        ProjectResolver resolver = new ProjectResolver(baseDir, resources);
        String path = Json.str(xslt, "path", "mapping.xsl");
        String systemId = resolver.systemIdFor(path);
        String content = xslt.get("content") != null ? Json.str(xslt, "content", "") : null;
        Map<String, Object> options = Json.obj(req, "options");
        String precedence = Json.str(options, "paramPrecedence", "header");
        String missing = Json.str(options, "missingParameter", "default");

        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));
        SimExchange exchange = input.copy();

        long t0 = System.nanoTime();
        Templates templates;
        boolean cached = false;
        try {
            if (content == null) {
                content = resolver.load(systemId);
            }
            String key = cacheKey(systemId, content, resources, baseDir);
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.upToDate()) {
                cached = true;
                diags.addAll(entry.warnings);
            } else {
                List<Diagnostic> compileDiags = new ArrayList<>();
                factory.setErrorListener(new CollectingListener(compileDiags));
                factory.setURIResolver(resolver);
                // Reflexive Java calls (java: namespaces) in the main module and in included modules.
                List<String> javaProblems = new ArrayList<>(javaExtensions.scan(content));
                resolver.setModuleListener(text -> javaProblems.addAll(javaExtensions.scan(text)));
                StreamSource src = new StreamSource(new StringReader(content));
                src.setSystemId(systemId);
                try {
                    Templates t = factory.newTemplates(src);
                    List<Diagnostic> warnings = new ArrayList<>(compileDiags);
                    entry = new CacheEntry(t, new LinkedHashMap<>(resolver.getFilesRead()), warnings);
                    cache.put(key, entry);
                    diags.addAll(compileDiags);
                } catch (TransformerException e) {
                    javaProblems.forEach(p -> diags.add(Diagnostic.error(p)));
                    diags.addAll(compileDiags);
                    if (compileDiags.stream().noneMatch(d -> d.getSeverity() == Diagnostic.Severity.ERROR)) {
                        diags.add(toDiagnostic(e, Diagnostic.Severity.ERROR));
                    }
                    return finish(result, false, "compile", diags, messages, input, null, t0, 0, cached, null, null);
                }
            }
            templates = entry.templates;
        } catch (TransformerException e) {
            diags.add(toDiagnostic(e, Diagnostic.Severity.ERROR));
            return finish(result, false, "compile", diags, messages, input, null, t0, 0, cached, null, null);
        }
        long compileNanos = System.nanoTime() - t0;

        // ---- parameters: properties first, headers override (Camel XsltBuilder order), then $exchange
        ExchangeHandle handle = new ExchangeHandle(exchange);
        List<Object> bindings = new ArrayList<>();
        Map<String, Boolean> declared = declaredParameters(templates);
        long t1 = System.nanoTime();
        try {
            Transformer tr = templates.newTransformer();
            tr.setURIResolver(resolver);
            List<Diagnostic> runtimeDiags = new ArrayList<>();
            tr.setErrorListener(new CollectingListener(runtimeDiags));
            SaxonCompat.captureMessages(tr, messages::add);

            Map<String, Object> first = "header".equals(precedence) ? input.getProperties() : input.getHeaders();
            Map<String, Object> second = "header".equals(precedence) ? input.getHeaders() : input.getProperties();
            Map<String, String> source = new LinkedHashMap<>();
            setParams(tr, first, "header".equals(precedence) ? "property" : "header", source, diags);
            setParams(tr, second, precedence, source, diags);
            tr.setParameter("exchange", handle);

            for (Map.Entry<String, Boolean> p : declared.entrySet()) {
                String name = p.getKey();
                String from = "exchange".equals(name) ? "exchange" : source.get(name);
                Object value = from == null || "exchange".equals(from) ? null
                        : ("header".equals(from) ? input.getHeaders().get(name) : input.getProperties().get(name));
                Json.Obj b = Json.o().with("name", name).with("source", from == null ? "default" : from)
                        .with("required", p.getValue());
                if (value != null) {
                    b.with("value", TypedValue.display(value)).with("type", TypedValue.typeOf(value));
                }
                bindings.add(b);
                if (from == null && "error".equals(missing)) {
                    diags.add(Diagnostic.error("Header/Property value not found for the parameter '" + name + "'"));
                }
            }
            if (diags.stream().anyMatch(d -> d.getSeverity() == Diagnostic.Severity.ERROR)) {
                return finish(result, false, "parameters", diags, messages, input, null, t0, compileNanos, cached, bindings, handle);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StreamSource in = new StreamSource(new ByteArrayInputStream(input.getBody()));
            in.setSystemId(ProjectResolver.MEMORY_BASE + "input.xml");
            try {
                tr.transform(in, new StreamResult(out));
            } catch (RuntimeException e) {
                // Saxon reports some dynamic errors as unchecked exceptions wrapping an XPathException.
                Throwable c = e.getCause();
                if (c instanceof TransformerException) {
                    throw (TransformerException) c;
                }
                throw new TransformerException(e.getMessage(), e);
            } finally {
                diags.addAll(runtimeDiags);
            }
            String enc = tr.getOutputProperty(OutputKeys.ENCODING);
            Charset cs = charset(enc);
            exchange.setBody(out.toByteArray(), cs);
            result.with("outputMethod", tr.getOutputProperty(OutputKeys.METHOD));
            long transformNanos = System.nanoTime() - t1;
            result.with("transformMs", transformNanos / 1_000_000.0);
            return finish(result, true, null, diags, messages, input, exchange, t0, compileNanos, cached, bindings, handle);
        } catch (TransformerException e) {
            if (diags.stream().noneMatch(d -> d.getSeverity() == Diagnostic.Severity.ERROR)) {
                diags.add(toDiagnostic(e, Diagnostic.Severity.ERROR));
            }
            result.with("transformMs", (System.nanoTime() - t1) / 1_000_000.0);
            return finish(result, false, "runtime", diags, messages, input, null, t0, compileNanos, cached, bindings, handle);
        }
    }

    private static void setParams(Transformer tr, Map<String, Object> values, String kind,
                                  Map<String, String> source, List<Diagnostic> diags) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            try {
                tr.setParameter(e.getKey(), e.getValue());
                source.put(e.getKey(), kind);
            } catch (IllegalArgumentException ex) {
                diags.add(Diagnostic.warning(kind + " '" + e.getKey() + "' cannot be passed as XSLT parameter: "
                        + ex.getMessage()));
            }
        }
    }

    /** Global parameters declared by the stylesheet (no namespace), with their "required" flag. */
    private static Map<String, Boolean> declaredParameters(Templates templates) {
        Map<String, Boolean> out = new TreeMap<>();
        if (templates instanceof TemplatesImpl) {
            XsltExecutable exe = ((TemplatesImpl) templates).getImplementation();
            for (Map.Entry<QName, XsltExecutable.ParameterDetails> e : exe.getGlobalParameters().entrySet()) {
                if (e.getKey().getNamespaceURI().isEmpty()) {
                    out.put(e.getKey().getLocalName(), e.getValue().isRequired());
                }
            }
        }
        return out;
    }

    private Json.Obj finish(Json.Obj result, boolean ok, String failedPhase, List<Diagnostic> diags,
                            List<XslMessage> messages, SimExchange input, SimExchange output, long t0,
                            long compileNanos, boolean cached, List<Object> bindings, ExchangeHandle handle) {
        result.with("ok", ok);
        if (failedPhase != null) {
            result.with("failedPhase", failedPhase);
        }
        List<Object> d = new ArrayList<>();
        diags.forEach(x -> d.add(x.toJson()));
        result.with("diagnostics", d);
        List<Object> m = new ArrayList<>();
        messages.forEach(x -> m.add(x.toJson()));
        result.with("messages", m);
        result.with("parameters", bindings == null ? List.of() : bindings);
        result.with("cpiCalls", handle == null ? List.of() : handle.calls);
        if (output != null) {
            result.with("output", output.toJson());
            result.with("changes", Json.o()
                    .with("headers", SimExchange.diff(input.getHeaders(), output.getHeaders()))
                    .with("properties", SimExchange.diff(input.getProperties(), output.getProperties())));
        }
        result.with("timing", Json.o()
                .with("compileMs", compileNanos / 1_000_000.0)
                .with("cached", cached)
                .with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
        result.with("engine", Json.o().with("profile", SaxonCompat.PROFILE).with("product", engineVersion()));
        return result;
    }

    private static Charset charset(String enc) {
        try {
            return enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String cacheKey(String systemId, String content, Map<String, String> resources, Path baseDir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(systemId.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(content.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : new TreeMap<>(resources).entrySet()) {
                md.update((byte) 0);
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
            md.update(String.valueOf(baseDir).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> stringMap(Map<String, Object> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) {
            m.forEach((k, v) -> {
                if (v != null) {
                    out.put(k, v.toString());
                }
            });
        }
        return out;
    }

    // ---------------------------------------------------------------- diagnostics

    static Diagnostic toDiagnostic(TransformerException e, Diagnostic.Severity severity) {
        SourceLocator loc = e.getLocator();
        Throwable t = e;
        String code = null;
        while (t != null) {
            if (t instanceof XPathException && ((XPathException) t).getErrorCodeQName() != null) {
                code = ((XPathException) t).getErrorCodeQName().getLocalPart();
                if (loc == null) {
                    loc = ((XPathException) t).getLocator();
                }
                break;
            }
            t = t.getCause();
        }
        String msg = e.getMessage();
        if (msg == null && e.getCause() != null) {
            msg = e.getCause().getMessage();
        }
        return new Diagnostic(severity, msg, code,
                loc == null ? null : displayId(loc.getSystemId()),
                loc == null ? -1 : loc.getLineNumber(),
                loc == null ? -1 : loc.getColumnNumber());
    }

    static String displayId(String systemId) {
        if (systemId == null) {
            return null;
        }
        if (systemId.startsWith(ProjectResolver.MEMORY_BASE)) {
            return systemId.substring(ProjectResolver.MEMORY_BASE.length());
        }
        return systemId;
    }

    static final class CollectingListener implements ErrorListener {
        private final List<Diagnostic> sink;

        CollectingListener(List<Diagnostic> sink) {
            this.sink = sink;
        }

        @Override
        public void warning(TransformerException e) {
            sink.add(toDiagnostic(e, Diagnostic.Severity.WARNING));
        }

        @Override
        public void error(TransformerException e) {
            sink.add(toDiagnostic(e, Diagnostic.Severity.ERROR));
        }

        @Override
        public void fatalError(TransformerException e) throws TransformerException {
            sink.add(toDiagnostic(e, Diagnostic.Severity.ERROR));
            throw e;
        }
    }
}
