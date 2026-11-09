package io.cpiwb.groovy;

import groovy.lang.GroovyInterceptable;
import groovy.lang.GroovyObjectSupport;
import io.cpiwb.core.Diagnostic;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * The {@code log} and {@code logger} variables a tenant binds for Groovy Script 1.x (docs/plan.md F49, F53):
 * SAP's internal {@code com.sap.it.script.logging.impl.ScriptLogger}, whose methods are not published. An
 * assumption: the usual logger calls ({@code trace} … {@code error}) are accepted and printed to the
 * console, with a note, and {@code isDebugEnabled()} and its siblings answer true; anything else raises
 * {@code UnsupportedInSimulator}.
 */
public final class TenantLogBinding extends GroovyObjectSupport implements GroovyInterceptable {

    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error");
    private static final Set<String> OBJECT_METHODS = Set.of("toString", "hashCode", "equals", "getClass", "getMetaClass",
            "asBoolean");
    private static final Set<String> ENABLED = Set.of("isTraceEnabled", "isDebugEnabled", "isInfoEnabled",
            "isWarnEnabled", "isErrorEnabled");

    private final String name;
    private final List<Diagnostic> diags;
    private boolean noted;

    public TenantLogBinding(String name, List<Diagnostic> diags) {
        this.name = name;
        this.diags = diags;
    }

    @Override
    public Object invokeMethod(String method, Object args) {
        if (OBJECT_METHODS.contains(method)) {
            return getMetaClass().invokeMethod(this, method, args);
        }
        if (ENABLED.contains(method)) {
            return true;
        }
        if (LEVELS.contains(method)) {
            if (!noted) {
                noted = true;
                diags.add(Diagnostic.info("'" + name + "' is bound on a tenant's Groovy Script 1.x, as SAP's "
                        + "ScriptLogger, whose methods are not published; the simulator prints " + name + "." + method + "(…) to the console."));
            }
            Object[] a = args instanceof Object[] ? (Object[]) args : new Object[] {args};
            StringBuilder sb = new StringBuilder("[" + name + "." + method + "]");
            for (Object o : a) {
                sb.append(' ').append(o);
            }
            PrintStream out = System.out;
            out.println(sb);
            return null;
        }
        throw new UnsupportedOperationException("UnsupportedInSimulator: '" + name + "' is bound on a tenant's Groovy "
                + "Script 1.x as SAP's ScriptLogger, whose methods are not published, so " + name + "." + method + "(…) is not simulated.");
    }

    @Override
    public Object getProperty(String property) {
        throw new UnsupportedOperationException("UnsupportedInSimulator: '" + name + "' is bound on a tenant's Groovy "
                + "Script 1.x as SAP's ScriptLogger, whose methods are not published, so " + name + "." + property + " is not simulated.");
    }

    @Override
    public String toString() {
        return name + " (SAP's ScriptLogger on a tenant's Groovy Script 1.x; simulated for the usual logger calls)";
    }
}
