package io.cpiwb.core;

import java.util.Map;

/** A compile/runtime/validation problem with an optional source location. */
public final class Diagnostic {

    public enum Severity { ERROR, WARNING, INFO }

    private final Severity severity;
    private final String message;
    private final String code;
    private final String systemId;
    private final int line;
    private final int column;

    public Diagnostic(Severity severity, String message, String code, String systemId, int line, int column) {
        this.severity = severity;
        this.message = message == null ? "" : message;
        this.code = code;
        this.systemId = systemId;
        this.line = line;
        this.column = column;
    }

    public static Diagnostic error(String message) {
        return new Diagnostic(Severity.ERROR, message, null, null, -1, -1);
    }

    public static Diagnostic warning(String message) {
        return new Diagnostic(Severity.WARNING, message, null, null, -1, -1);
    }

    public static Diagnostic info(String message) {
        return new Diagnostic(Severity.INFO, message, null, null, -1, -1);
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> toJson() {
        Json.Obj o = Json.o()
                .with("severity", severity.name().toLowerCase())
                .with("message", message);
        if (code != null) {
            o.with("code", code);
        }
        if (systemId != null) {
            o.with("systemId", systemId);
        }
        if (line > 0) {
            o.with("line", line);
        }
        if (column > 0) {
            o.with("column", column);
        }
        return o;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(severity.name());
        if (systemId != null || line > 0) {
            sb.append(" [");
            if (systemId != null) {
                sb.append(systemId);
            }
            if (line > 0) {
                sb.append(systemId != null ? ":" : "line ").append(line);
                if (column > 0) {
                    sb.append(':').append(column);
                }
            }
            sb.append(']');
        }
        if (code != null) {
            sb.append(' ').append(code);
        }
        return sb.append(": ").append(message).toString();
    }
}
