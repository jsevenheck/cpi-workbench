package io.cpiwb.groovy;

import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingMethodException;
import io.cpiwb.core.Diagnostic;

import java.util.List;

/**
 * The {@code log} and {@code logger} variables a tenant binds for Groovy Script 1.x: SAP's internal
 * {@code com.sap.it.script.logging.impl.ScriptLogger} (docs/plan.md F49, F53). Its public methods, as the
 * fingerprint listed them (F58): {@code logInfo(Object[])}, {@code logWarnings(Object[])},
 * {@code logErrors(Object[])}, {@code logErrors(LogMessage)}, {@code logErrors(LogMessage, Object[])},
 * {@code getLogger(Class)} and {@code getCategoryLogger(Class)}. It has no {@code info}, {@code debug} and so
 * on, so calling them fails as on a tenant. Where a tenant writes {@code logInfo} and its siblings is not
 * known (not to the MPL, an assumption); the simulator prints them to the console with a note. The loggers
 * that {@code getLogger} returns ({@code ILogger}) are not published and raise {@code UnsupportedInSimulator}.
 */
public final class TenantLogBinding extends GroovyObjectSupport {

    private static final String SAP_CLASS = "com.sap.it.script.logging.impl.ScriptLogger";

    private final String name;
    private final List<Diagnostic> diags;
    private boolean noted;

    public TenantLogBinding(String name, List<Diagnostic> diags) {
        this.name = name;
        this.diags = diags;
    }

    public void logInfo(Object... args) {
        print("logInfo", args);
    }

    public void logWarnings(Object... args) {
        print("logWarnings", args);
    }

    public void logErrors(Object... args) {
        print("logErrors", args);
    }

    public Object getLogger(Class<?> type) {
        throw unsupported("getLogger");
    }

    public Object getCategoryLogger(Class<?> type) {
        throw unsupported("getCategoryLogger");
    }

    /** Any other method fails as on a tenant, whose ScriptLogger has no such method (e.g. {@code info}). */
    public Object methodMissing(String method, Object args) {
        Object[] a = args instanceof Object[] ? (Object[]) args : new Object[] {args};
        throw new MissingMethodException(method, TenantLogBinding.class, a) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
                return "No signature of method: " + SAP_CLASS + "." + method + "() (the '" + name + "' variable of Groovy "
                        + "Script 1.x). A tenant's ScriptLogger has logInfo, logWarnings, logErrors, getLogger and "
                        + "getCategoryLogger only; use messageLogFactory for the MPL.";
            }

            @Override
            public String toString() {
                return MissingMethodException.class.getName() + ": " + getMessage();
            }
        };
    }

    private void print(String method, Object[] args) {
        if (!noted) {
            noted = true;
            diags.add(Diagnostic.info("'" + name + "' is SAP's ScriptLogger on a tenant; where it writes " + method
                    + "(...) is not known (not to the MPL), so the simulator prints it to the console."));
        }
        StringBuilder sb = new StringBuilder("[" + name + "." + method + "]");
        for (Object o : args == null ? new Object[] {null} : args) {
            sb.append(' ').append(o);
        }
        System.out.println(sb);
    }

    private UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("UnsupportedInSimulator: " + name + "." + method + "(...) returns SAP's "
                + "ILogger on a tenant, whose methods are not published.");
    }

    @Override
    public String toString() {
        return SAP_CLASS + " (simulated: logInfo, logWarnings, logErrors)";
    }
}
