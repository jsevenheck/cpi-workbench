package com.sap.it.script.v2.api.exceptions;

import org.xml.sax.SAXParseException;

import java.util.List;

/** An XML schema validation failed (Script API v2). */
public class SchemaValidationException extends ScenarioExecutionException {

    private final Object schema;
    private final List<SAXParseException> errors;
    private final List<SAXParseException> fatalErrors;
    private final List<SAXParseException> warnings;

    public SchemaValidationException(Object schema, List<SAXParseException> errors,
                                     List<SAXParseException> fatalErrors, List<SAXParseException> warnings) {
        super(message(schema, errors, fatalErrors, warnings));
        this.schema = schema;
        this.errors = errors;
        this.fatalErrors = fatalErrors;
        this.warnings = warnings;
    }

    public Object getSchema() { return schema; }
    public List<SAXParseException> getErrors() { return errors; }
    public List<SAXParseException> getFatalErrors() { return fatalErrors; }
    public List<SAXParseException> getWarnings() { return warnings; }

    protected static String message(Object schema, List<SAXParseException> errors,
                                    List<SAXParseException> fatalErrors, List<SAXParseException> warnings) {
        StringBuilder sb = new StringBuilder("Validation failed for: ").append(schema);
        append(sb, "fatal errors", fatalErrors);
        append(sb, "errors", errors);
        append(sb, "warnings", warnings);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, List<SAXParseException> list) {
        if (list != null && !list.isEmpty()) {
            sb.append("\n").append(label).append(":");
            for (SAXParseException e : list) {
                sb.append("\n  line ").append(e.getLineNumber()).append(", column ").append(e.getColumnNumber())
                        .append(": ").append(e.getMessage());
            }
        }
    }
}
