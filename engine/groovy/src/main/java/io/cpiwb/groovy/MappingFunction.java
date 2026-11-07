package io.cpiwb.groovy;

import com.sap.it.api.mapping.MappingContext;
import com.sap.it.api.mapping.Output;
import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import org.apache.camel.Exchange;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A custom function of a Message Mapping script, run on its own. The signature decides the execution
 * type, as community guides describe CPI (plan F44; CPI has no "All values of a queue"):
 * {@code def String f(String a, MappingContext context)} is "Single Values" (one call here; CPI calls
 * it once per value), {@code def void f(String[] a, Output output, MappingContext context)} is "All
 * values of a context": an argument is a list of contexts and the function is called once per context,
 * without context changes in its arrays, as SAP's PI documentation describes the type (plan F42).
 * Each call gets an Output of its own; joining the results with a context change is an assumption from
 * that documentation. SUPPRESS and
 * xsi:nil are marker strings of the simulator, compared by identity; CPI's own values are not known.
 */
final class MappingFunction {

    static final String CONTEXT_CHANGE = new String("[ContextChange]");
    static final String SUPPRESS = new String("[SUPPRESS]");
    static final String XSI_NIL = new String("[xsi:nil]");

    enum Kind { VALUE, QUEUE, OUTPUT, CONTEXT }

    final Method method;
    final List<Kind> kinds;

    private MappingFunction(Method method, List<Kind> kinds) {
        this.method = method;
        this.kinds = kinds;
    }

    boolean advanced() {
        return kinds.contains(Kind.OUTPUT) || kinds.contains(Kind.QUEUE);
    }

    List<String> parameters() {
        List<String> p = new ArrayList<>();
        for (Kind k : kinds) {
            p.add(switch (k) {
                case VALUE -> "String";
                case QUEUE -> "String[]";
                case OUTPUT -> "Output";
                case CONTEXT -> "MappingContext";
            });
        }
        return p;
    }

    String signature() {
        return method.getName() + "(" + String.join(", ", parameters()) + ")";
    }

    /** {@code result.mapping} before the call; {@link #call} adds the execution and the returned value or queue. */
    Json.Obj describe() {
        return Json.o().with("function", method.getName()).with("signature", signature()).with("parameters", parameters());
    }

    /** The mapping function of that name, or null. A one-parameter function on an Object or a Message is a script step. */
    static MappingFunction find(Class<?> scriptClass, String name) {
        for (Method m : candidates(scriptClass)) {
            if (m.getName().equals(name)) {
                MappingFunction f = of(m);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Signatures of all mapping functions in the script, for the function picker. */
    static List<String> list(Class<?> scriptClass) {
        List<String> out = new ArrayList<>();
        for (Method m : candidates(scriptClass)) {
            MappingFunction f = of(m);
            if (f != null && !out.contains(f.signature())) {
                out.add(f.signature());
            }
        }
        out.sort(null);
        return out;
    }

    private static List<Method> candidates(Class<?> scriptClass) {
        List<Method> out = new ArrayList<>();
        for (Method m : scriptClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod) && !m.isSynthetic() && !m.isBridge()
                    && !m.getName().equals("run") && !m.getName().contains("$") && !fieldSetter(scriptClass, m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** The setter Groovy generates for an {@code @Field} variable of the script: not a mapping function. */
    private static boolean fieldSetter(Class<?> cls, Method m) {
        String n = m.getName();
        if (!n.startsWith("set") || n.length() < 4 || m.getParameterCount() != 1) {
            return false;
        }
        String field = Character.toLowerCase(n.charAt(3)) + n.substring(4);
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (f.getName().equals(field) || f.getName().equals(n.substring(3))) {
                return true;
            }
        }
        return false;
    }

    private static MappingFunction of(Method m) {
        Class<?>[] types = m.getParameterTypes();
        if (types.length == 0 || (types.length == 1 && !(types[0] == String.class || types[0] == String[].class))) {
            return null; // processData(message) and friends
        }
        List<Kind> kinds = new ArrayList<>();
        for (Class<?> t : types) {
            if (t == String.class || t == Object.class) {
                kinds.add(Kind.VALUE);
            } else if (t == String[].class || t == Object[].class) {
                kinds.add(Kind.QUEUE);
            } else if (t == Output.class) {
                kinds.add(Kind.OUTPUT);
            } else if (t == MappingContext.class) {
                kinds.add(Kind.CONTEXT);
            } else {
                return null;
            }
        }
        return new MappingFunction(m, kinds);
    }

    // ---------------------------------------------------------------- run

    /** Calls the function and adds its result to {@code result} (from {@link #describe}). */
    void call(Object script, List<Object> args, Exchange exchange, List<Diagnostic> diags, Json.Obj result)
            throws Exception {
        int values = (int) kinds.stream().filter(k -> k == Kind.VALUE || k == Kind.QUEUE).count();
        if (args.size() > values) {
            diags.add(Diagnostic.warning(args.size() + " arguments given, " + signature() + " takes " + values
                    + "; the rest are ignored."));
        }
        if (kinds.contains(Kind.OUTPUT) && method.getReturnType() != void.class) {
            diags.add(Diagnostic.warning("A function with an Output parameter returns its values through Output; "
                    + "community guides declare such functions void and report that CPI rejects other return types."));
        }
        if (kinds.contains(Kind.QUEUE) && !kinds.contains(Kind.OUTPUT)) {
            diags.add(Diagnostic.warning("The function takes String[] but has no Output parameter: its return value is "
                    + "shown, but CPI's documented advanced functions return their values through Output."));
        }
        // ---- arguments: VALUE takes a string, QUEUE a list of contexts
        List<Object> single = new ArrayList<>();
        List<List<List<String>>> queues = new ArrayList<>();
        int a = 0;
        for (Kind k : kinds) {
            if (k == Kind.VALUE) {
                single.add(a < args.size() ? value(args.get(a), a) : missing(a, "String", "an empty string", "", diags));
                queues.add(null);
                a++;
            } else if (k == Kind.QUEUE) {
                queues.add(a < args.size() ? contexts(args.get(a), a)
                        : missing(a, "String[]", "one empty context", List.of(List.of()), diags));
                single.add(null);
                a++;
            } else {
                single.add(null);
                queues.add(null);
            }
        }
        SimOutput output = new SimOutput();
        Context context = new Context(exchange);
        if (!advanced()) {
            Object r = method.invoke(script, build(single, queues, -1, output, context));
            result.with("execution", "single");
            result.with("returned", r == null ? null : String.valueOf(r));
            return;
        }
        int calls = -1; // the number of contexts
        for (int i = 0; i < kinds.size(); i++) {
            if (queues.get(i) != null) {
                int n = queues.get(i).size();
                if (calls >= 0 && n != calls) {
                    throw new IllegalArgumentException("Every String[] argument needs the same number of contexts "
                            + "(the function is called once per context); they have " + calls + " and " + n + ".");
                }
                calls = n;
            }
        }
        calls = Math.max(calls, 1);
        // Each call gets an Output of its own (so clear() drops only its values); the results are joined.
        Object returned = null;
        for (int c = 0; c < calls; c++) {
            SimOutput own = new SimOutput();
            returned = method.invoke(script, build(single, queues, c, own, context));
            if (c > 0) {
                output.addContextChange();
            }
            output.entries.addAll(own.entries);
        }
        result.with("execution", "context");
        if (method.getReturnType() != void.class) {
            result.with("returned", returned == null ? null : String.valueOf(returned));
        }
        result.with("queue", output.toJson());
    }

    /** Arguments of one call: context {@code ctx} of each queue argument. */
    private Object[] build(List<Object> single, List<List<List<String>>> queues, int ctx, Output output, MappingContext context) {
        Object[] out = new Object[kinds.size()];
        for (int i = 0; i < kinds.size(); i++) {
            switch (kinds.get(i)) {
                case VALUE -> out[i] = single.get(i);
                case OUTPUT -> out[i] = output;
                case CONTEXT -> out[i] = context;
                case QUEUE -> {
                    List<String> values = queues.get(i).get(ctx);
                    out[i] = method.getParameterTypes()[i] == Object[].class ? values.toArray(new Object[0]) : values.toArray(new String[0]);
                }
            }
        }
        return out;
    }

    private static <T> T missing(int index, String type, String what, T value, List<Diagnostic> diags) {
        diags.add(Diagnostic.info("Argument " + (index + 1) + " (" + type + ") has no value; the simulator passes " + what + "."));
        return value;
    }

    private static String value(Object v, int index) {
        if (v == null || v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        throw new IllegalArgumentException("Argument " + (index + 1) + " is a single value: give a string.");
    }

    /** A queue argument: a list of contexts, or a flat list of values (one context). */
    @SuppressWarnings("unchecked")
    private static List<List<String>> contexts(Object v, int index) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("Argument " + (index + 1) + " is a queue (String[]): give a list of contexts, "
                    + "e.g. [[\"A\", \"B\"], [\"C\"]].");
        }
        List<Object> list = (List<Object>) v;
        boolean nested = !list.isEmpty() && list.stream().allMatch(o -> o instanceof List);
        List<List<String>> out = new ArrayList<>();
        if (!nested) {
            out.add(entries(list, index));
        } else {
            for (Object c : list) {
                out.add(entries((List<Object>) c, index));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> entries(List<Object> values, int index) {
        List<String> out = new ArrayList<>();
        for (Object o : values) {
            if (o instanceof Map && Json.bool((Map<String, Object>) o, "suppress", false)) {
                out.add(SUPPRESS);
            } else if (o instanceof Map && Json.bool((Map<String, Object>) o, "xsiNil", false)) {
                out.add(XSI_NIL);
            } else if (o instanceof List || o instanceof Map) {
                throw new IllegalArgumentException("Argument " + (index + 1) + ": a context holds strings, "
                        + "{\"suppress\": true} or {\"xsiNil\": true}.");
            } else {
                out.add(o == null ? null : String.valueOf(o));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- API objects

    /** Headers and exchange properties of the run's exchange (a class, not a record: no public exchange()). */
    private static final class Context implements MappingContext {
        private final Exchange exchange;

        Context(Exchange exchange) { this.exchange = exchange; }
        public Object getHeader(String name) { return exchange.getIn().getHeader(name); }
        public void setHeader(String name, Object value) { exchange.getIn().setHeader(name, value); }
        public Object getProperty(String name) { return exchange.getProperty(name); }
        public void setProperty(String name, Object value) { exchange.setProperty(name, value); }
    }

    /** Collects the entries; a value that is a marker counts as that marker. */
    static final class SimOutput implements Output {
        private final List<Object> entries = new ArrayList<>();

        public void addValue(Object value) { entries.add(value); }
        public void addContextChange() { entries.add(CONTEXT_CHANGE); }
        public void addSuppress() { entries.add(SUPPRESS); }
        public void addXSINIL() { entries.add(XSI_NIL); }
        public void clear() { entries.clear(); }
        public boolean isContextChange(Object value) { return value == CONTEXT_CHANGE; }
        public boolean isSuppress(Object value) { return value == SUPPRESS; }
        public boolean isXSINIL(Object value) { return value == XSI_NIL; }

        /** Contexts of values; SUPPRESS and xsi:nil as {"suppress": true} and {"xsiNil": true}. */
        List<Object> toJson() {
            List<Object> out = new ArrayList<>();
            List<Object> ctx = new ArrayList<>();
            for (Object e : entries) {
                if (e == CONTEXT_CHANGE) {
                    out.add(ctx);
                    ctx = new ArrayList<>();
                } else if (e == SUPPRESS) {
                    ctx.add(Json.o().with("suppress", true));
                } else if (e == XSI_NIL) {
                    ctx.add(Json.o().with("xsiNil", true));
                } else {
                    ctx.add(e == null ? null : String.valueOf(e));
                }
            }
            out.add(ctx);
            return out;
        }
    }
}
