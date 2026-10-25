package io.cpiwb.xml;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reflexive Java extension functions ({@code xmlns:date="java:java.util.Date"}, {@code date:new()}),
 * which CPI's Saxon-EE offers and Saxon-HE does not. Implemented the way Saxon documents them:
 * the namespace names the class, {@code new} calls a constructor, a static method takes the
 * function's arguments, an instance method takes the object as first argument; hyphenated names map
 * to camelCase. Classes are found by scanning stylesheet modules for {@code java:} namespaces and
 * registering one integrated function per method name.
 */
public final class JavaExtensions {

    private static final Pattern JAVA_NS = Pattern.compile("xmlns:[\\w.-]+\\s*=\\s*[\"']java:([\\w.$]+)[\"']");
    public static final int MAX_ARGS = 20;

    /** Registers one function (namespace, local name) with the XSLT engine. */
    @FunctionalInterface
    public interface Registrar {
        void register(String namespace, String localName, Invoker invoker);
    }

    @FunctionalInterface
    public interface Invoker {
        Object invoke(Object[] args) throws Exception;
    }

    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    private final Registrar registrar;

    public JavaExtensions(Registrar registrar) {
        this.registrar = registrar;
    }

    /**
     * Registers the classes named by {@code java:} namespaces in a stylesheet module. Returns the
     * problems found (class missing); the stylesheet then fails to compile with Saxon's own error.
     */
    public List<String> scan(String moduleText) {
        List<String> problems = new ArrayList<>();
        if (moduleText == null || !moduleText.contains("java:")) {
            return problems;
        }
        Matcher m = JAVA_NS.matcher(moduleText);
        while (m.find()) {
            String className = m.group(1);
            if (registered.contains(className)) {
                continue;
            }
            Class<?> c;
            try {
                c = Class.forName(className);
            } catch (ClassNotFoundException | LinkageError e) {
                problems.add("Java class " + className + " is not available to the simulator"
                        + (isNativeImage() ? " (the native engine only includes common JDK classes)" : ""));
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            names.add("new");
            for (Method method : c.getMethods()) {
                names.add(method.getName());
            }
            for (String name : names) {
                for (String alias : aliases(name)) {
                    registrar.register("java:" + className, alias, args -> invoke(c, name, args));
                }
            }
            registered.add(className);
        }
        return problems;
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    /**
     * Names under which a method can be called. Saxon maps a function name to a method by dropping
     * each hyphen and upper-casing the next character, so {@code toUpperCase} is reachable as
     * {@code to-upper-case} and {@code randomUUID} as {@code random-UUID}; the common spellings are
     * registered.
     */
    static Set<String> aliases(String name) {
        Set<String> out = new LinkedHashSet<>();
        out.add(name);
        StringBuilder perChar = new StringBuilder();
        StringBuilder perRun = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean upper = Character.isUpperCase(ch);
            boolean prevUpper = i > 0 && Character.isUpperCase(name.charAt(i - 1));
            boolean nextUpper = i + 1 < name.length() && Character.isUpperCase(name.charAt(i + 1));
            if (upper && i > 0) {
                perChar.append('-').append(Character.toLowerCase(ch));
            } else {
                perChar.append(ch);
            }
            if (upper && i > 0 && !prevUpper) {
                perRun.append('-').append(nextUpper ? ch : Character.toLowerCase(ch));
            } else {
                perRun.append(ch);
            }
        }
        out.add(perChar.toString());
        out.add(perRun.toString());
        return out;
    }

    // ---------------------------------------------------------------- invocation

    /** Calls the best matching constructor or method; arguments are already plain Java values. */
    public static Object invoke(Class<?> c, String name, Object[] args) throws Exception {
        Executable best = null;
        Object[] bestArgs = null;
        Object target = null;
        int bestScore = -1;
        List<Executable> candidates = new ArrayList<>();
        if ("new".equals(name)) {
            for (Constructor<?> k : c.getConstructors()) {
                candidates.add(k);
            }
        } else {
            for (Method method : c.getMethods()) {
                if (method.getName().equals(name)) {
                    candidates.add(method);
                }
            }
        }
        for (Executable e : candidates) {
            boolean isStatic = e instanceof Constructor || Modifier.isStatic(e.getModifiers());
            int offset = isStatic ? 0 : 1;
            Object[] callArgs = args;
            if (e.isVarArgs() && args.length - offset >= e.getParameterCount() - 1) {
                callArgs = packVarArgs(args, offset, e.getParameterCount(),
                        e.getParameterTypes()[e.getParameterCount() - 1].getComponentType());
                if (callArgs == null) {
                    continue;
                }
            } else if (e.getParameterCount() != args.length - offset) {
                continue;
            }
            Object self = null;
            if (!isStatic) {
                self = convert(args[0], c);
                if (self == NO_MATCH || self == null) {
                    continue;
                }
            }
            Object[] converted = new Object[e.getParameterCount()];
            int score = 0;
            boolean ok = true;
            Class<?>[] types = e.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                Object a = callArgs[i + offset];
                Object v = convert(a, types[i]);
                if (v == NO_MATCH) {
                    ok = false;
                    break;
                }
                converted[i] = v;
                score += a != null && box(types[i]).isInstance(a) ? 2 : 1;
            }
            if (ok && score > bestScore) {
                best = e;
                bestArgs = converted;
                target = self;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No public " + ("new".equals(name) ? "constructor" : "method " + name)
                    + " of " + c.getName() + " accepts " + args.length + " argument(s) of these types");
        }
        try {
            return best instanceof Constructor ? ((Constructor<?>) best).newInstance(bestArgs)
                    : ((Method) best).invoke(target, bestArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    private static final Object NO_MATCH = new Object();

    /** Collects the trailing arguments of a varargs call into one array argument. */
    private static Object[] packVarArgs(Object[] args, int offset, int paramCount, Class<?> component) {
        int fixed = offset + paramCount - 1;
        Object array = java.lang.reflect.Array.newInstance(component, args.length - fixed);
        for (int i = fixed; i < args.length; i++) {
            Object v = convert(args[i], component);
            if (v == NO_MATCH) {
                return null;
            }
            java.lang.reflect.Array.set(array, i - fixed, v);
        }
        Object[] out = new Object[fixed + 1];
        System.arraycopy(args, 0, out, 0, fixed);
        out[fixed] = array;
        return out;
    }

    private static Object convert(Object a, Class<?> type) {
        if (a == null) {
            return type.isPrimitive() ? NO_MATCH : null;
        }
        Class<?> boxed = box(type);
        if (boxed.isInstance(a)) {
            return a;
        }
        if (type == String.class || type == CharSequence.class) {
            return a instanceof List ? NO_MATCH : String.valueOf(a);
        }
        if (a instanceof Number || a instanceof String) {
            try {
                String s = a.toString().trim();
                Number n = a instanceof Number ? (Number) a : null;
                if (boxed == Integer.class) {
                    return n != null ? Integer.valueOf(n.intValue()) : Integer.valueOf(s);
                }
                if (boxed == Long.class) {
                    return n != null ? Long.valueOf(n.longValue()) : Long.valueOf(s);
                }
                if (boxed == Short.class) {
                    return n != null ? Short.valueOf(n.shortValue()) : Short.valueOf(s);
                }
                if (boxed == Byte.class) {
                    return n != null ? Byte.valueOf(n.byteValue()) : Byte.valueOf(s);
                }
                if (boxed == Double.class) {
                    return n != null ? Double.valueOf(n.doubleValue()) : Double.valueOf(s);
                }
                if (boxed == Float.class) {
                    return n != null ? Float.valueOf(n.floatValue()) : Float.valueOf(s);
                }
                if (boxed == BigDecimal.class) {
                    return new BigDecimal(s);
                }
                if (boxed == BigInteger.class) {
                    return new BigInteger(s);
                }
            } catch (NumberFormatException e) {
                return NO_MATCH;
            }
        }
        if (boxed == Character.class && a instanceof String && ((String) a).length() == 1) {
            return ((String) a).charAt(0);
        }
        if (boxed == Boolean.class && a instanceof String) {
            return Boolean.valueOf((String) a);
        }
        return NO_MATCH;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) {
            return t;
        }
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == boolean.class) return Boolean.class;
        if (t == char.class) return Character.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return Void.class;
    }
}
