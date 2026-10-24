package io.cpiwb.xml;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * XML Schema (XSD 1.0) validation with the JDK's JAXP validator, as a stand-in for the CPI
 * <em>XML Validator</em> step. Which validator CPI uses internally is still open (Q-V1).
 */
public final class XsdStep {

    /**
     * Request: {@code {"schema": {"content": "...", "path": "schemas/order.xsd"}, "baseDir": "...",
     * "resources": {...}, "input": {"body": "..."}}}
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        Map<String, Object> schemaRef = Json.obj(req, "schema");
        if (schemaRef == null) {
            throw new IllegalArgumentException("Missing 'schema'");
        }
        String baseDirStr = Json.str(req, "baseDir", null);
        Path baseDir = baseDirStr == null || baseDirStr.isBlank() ? null : Path.of(baseDirStr);
        ProjectResolver resolver = new ProjectResolver(baseDir, XsltStep.stringMap(Json.obj(req, "resources")));
        String systemId = resolver.systemIdFor(Json.str(schemaRef, "path", "schema.xsd"));
        SimExchange input = SimExchange.fromJson(Json.obj(req, "input"));

        List<Diagnostic> schemaDiags = new ArrayList<>();
        List<Diagnostic> docDiags = new ArrayList<>();
        Json.Obj result = Json.o();
        try {
            String content = schemaRef.get("content") != null
                    ? Json.str(schemaRef, "content", "") : resolver.load(systemId);
            SchemaFactory sf = SchemaFactory.newDefaultInstance();
            sf.setResourceResolver(resolver);
            sf.setErrorHandler(new Collector(schemaDiags, "schema"));
            StreamSource xsd = new StreamSource(new StringReader(content));
            xsd.setSystemId(systemId);
            Schema schema = sf.newSchema(xsd);

            Validator v = schema.newValidator();
            v.setResourceResolver(resolver);
            v.setErrorHandler(new Collector(docDiags, "input"));
            StreamSource doc = new StreamSource(new ByteArrayInputStream(input.getBody()));
            doc.setSystemId(ProjectResolver.MEMORY_BASE + "input.xml");
            try {
                v.validate(doc);
            } catch (SAXParseException e) {
                // already collected by the error handler
            }
            boolean valid = docDiags.stream().noneMatch(d -> d.getSeverity() == Diagnostic.Severity.ERROR);
            result.with("ok", true).with("valid", valid);
        } catch (SAXException | javax.xml.transform.TransformerException | IllegalStateException e) {
            if (schemaDiags.stream().noneMatch(d -> d.getSeverity() == Diagnostic.Severity.ERROR)) {
                schemaDiags.add(Diagnostic.error("Schema cannot be loaded: " + e.getMessage()));
            }
            result.with("ok", false).with("failedPhase", "schema").with("valid", false);
        } catch (IOException e) {
            schemaDiags.add(Diagnostic.error(e.getMessage()));
            result.with("ok", false).with("failedPhase", "schema").with("valid", false);
        }
        List<Object> all = new ArrayList<>();
        schemaDiags.forEach(d -> all.add(d.toJson()));
        docDiags.forEach(d -> all.add(d.toJson()));
        result.with("diagnostics", all);
        result.with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
        result.with("engine", Json.o().with("validator", "JDK JAXP (XML Schema 1.0)"));
        return result;
    }

    private static final class Collector implements ErrorHandler {
        private final List<Diagnostic> sink;
        private final String origin;

        Collector(List<Diagnostic> sink, String origin) {
            this.sink = sink;
            this.origin = origin;
        }

        private Diagnostic d(SAXParseException e, Diagnostic.Severity s) {
            String id = XsltStep.displayId(e.getSystemId());
            return new Diagnostic(s, e.getMessage(), null, id == null ? origin : id, e.getLineNumber(), e.getColumnNumber());
        }

        @Override
        public void warning(SAXParseException e) {
            sink.add(d(e, Diagnostic.Severity.WARNING));
        }

        @Override
        public void error(SAXParseException e) {
            sink.add(d(e, Diagnostic.Severity.ERROR));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            sink.add(d(e, Diagnostic.Severity.ERROR));
            throw e;
        }
    }
}
