package io.cpiwb.groovy;

import com.sap.it.api.msglog.MessageLogFactory;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovySystem;
import groovy.lang.Script;
import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TypedValue;
import io.cpiwb.groovy.camel.Exchanges;
import org.apache.camel.Exchange;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.syntax.SyntaxException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates the Cloud Integration <em>Groovy Script</em> step: compiles the script with the
 * profile's Groovy version, calls the configured function with a CPI {@code Message} backed by a
 * Camel exchange and reports body, headers, properties, MPL entries, console output and errors.
 */
public final class ScriptRunner {

    /** Script API generation of this engine: "v1" (Groovy 2.4) or "v2" (Groovy 4). */
    private final String apiVersion;
    private final Class<? extends MessageSupport> defaultMessageClass;
    private final Map<String, Class<?>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
            return size() > 32;
        }
    };

    public ScriptRunner(String apiVersion, Class<? extends MessageSupport> defaultMessageClass) {
        this.apiVersion = apiVersion;
        this.defaultMessageClass = defaultMessageClass;
    }

    public synchronized Map<String, Object> run(Map<String, Object> req) throws Exception {
        long t0 = System.nanoTime();
        List<Diagnostic> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        Map<String, Object> scriptRef = Json.obj(req, "script");
        if (scriptRef == null) {
            throw new IllegalArgumentException("Missing 'script'");
        }
        String baseDirStr = Json.str(req, "baseDir", null);
        Path baseDir = baseDirStr == null || baseDirStr.isBlank() ? null : Path.of(baseDirStr).toAbsolutePath().normalize();
        String path = Json.str(scriptRef, "path", "script.groovy");
        String source = scriptRef.get("content") != null ? Json.str(scriptRef, "content", "")
                : Files.readString(project(baseDir, path), StandardCharsets.UTF_8);
        String function = Json.str(req, "function", "processData");
        Map<String, Object> options = Json.obj(req, "options");

        Platform platform = Platform.fromJson(platformData(req, baseDir), baseDir);
        Platform.setCurrent(platform);
        lint(source, diags);

        // ---- compile (cached by source, name and classpath)
        List<String> classpath = new ArrayList<>();
        for (Object o : Json.arr(req, "classpath")) {
            classpath.add(o.toString());
        }
        String className = className(path);
        Class<?> scriptClass;
        boolean cached;
        String key = hash(className + "\0" + source + "\0" + classpath + "\0" + baseDir);
        long tc = System.nanoTime();
        scriptClass = cache.get(key);
        cached = scriptClass != null;
        if (scriptClass == null) {
            try {
                scriptClass = compile(source, className, classpath, baseDir);
                cache.put(key, scriptClass);
            } catch (MultipleCompilationErrorsException e) {
                compileDiagnostics(e, diags);
                return finish(result, false, "compile", diags, null, null, platform, "", t0, 0, false, null);
            } catch (CompilationFailedException e) {
                diags.add(Diagnostic.error(e.getMessage()));
                return finish(result, false, "compile", diags, null, null, platform, "", t0, 0, false, null);
            }
        }
        double compileMs = (System.nanoTime() - tc) / 1_000_000.0;

        Method fn = findFunction(scriptClass, function);
        if (fn == null) {
            diags.add(Diagnostic.error("Function '" + function + "' with one parameter was not found in the script. "
                    + "The Groovy Script step calls " + function + "(message)."));
            return finish(result, false, "compile", diags, null, null, platform, "", t0, compileMs, cached, null);
        }

        // ---- build the message
        SimExchange in = SimExchange.fromJson(Json.obj(req, "input"));
        Exchange exchange = Exchanges.create();
        String bodyType = Json.str(options, "bodyType", "String");
        exchange.getIn().setBody(inputBody(in, bodyType));
        exchange.getIn().getHeaders().putAll(in.getHeaders());
        exchange.getProperties().putAll(in.getProperties());
        MessageSupport message = newMessage(fn.getParameterTypes()[0], exchange, diags);

        // ---- run
        StringWriter console = new StringWriter();
        PrintWriter out = new PrintWriter(console, true);
        Binding binding = new Binding();
        binding.setVariable("messageLogFactory", new MessageLogFactory());
        binding.setVariable("out", out);
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream sysOut = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(sysOut, true, StandardCharsets.UTF_8);
        Object returned = null;
        boolean ok = true;
        long tr = System.nanoTime();
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            System.setOut(capture);
            System.setErr(capture);
            Thread.currentThread().setContextClassLoader(scriptClass.getClassLoader());
            Script script = InvokerHelper.createScript(scriptClass, binding);
            returned = script.invokeMethod(function, new Object[] {message});
        } catch (Throwable e) {
            ok = false;
            diags.add(runtimeDiagnostic(e, className));
            result.with("stackTrace", stackTrace(e, className));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            Thread.currentThread().setContextClassLoader(oldCl);
        }
        double runMs = (System.nanoTime() - tr) / 1_000_000.0;
        out.flush();
        String consoleText = console + sysOut.toString(StandardCharsets.UTF_8);
        if (returned != null && !(returned instanceof MessageSupport)) {
            diags.add(Diagnostic.warning("The function returned " + returned.getClass().getName()
                    + " instead of the message; the simulator continues with the message object that was passed in."));
        }
        result.with("runMs", runMs);
        Json.Obj output = ok ? describeOutput(exchange, in.getCharset(), diags) : null;
        Json.Obj changes = null;
        if (ok) {
            changes = Json.o()
                    .with("headers", SimExchange.diff(in.getHeaders(), exchange.getIn().getHeaders()))
                    .with("properties", SimExchange.diff(in.getProperties(), exchange.getProperties()));
        }
        return finish(result, ok, ok ? null : "runtime", diags, output, changes, platform, consoleText, t0,
                compileMs, cached, message);
    }

    // ---------------------------------------------------------------- compile

    private Class<?> compile(String source, String className, List<String> classpath, Path baseDir) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String jar : classpath) {
            urls.add(project(baseDir, jar).toUri().toURL());
        }
        ClassLoader parent = getClass().getClassLoader();
        if (!urls.isEmpty()) {
            parent = new URLClassLoader(urls.toArray(new URL[0]), parent);
        }
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setSourceEncoding("UTF-8");
        GroovyClassLoader gcl = new GroovyClassLoader(parent, cfg);
        GroovyCodeSource code = new GroovyCodeSource(source, className + ".groovy", "/groovy/script");
        code.setCachable(false);
        return gcl.parseClass(code);
    }

    private static Path project(Path baseDir, String relative) {
        if (baseDir == null) {
            throw new IllegalArgumentException("No project folder: cannot read " + relative);
        }
        Path p = baseDir.resolve(relative).normalize();
        if (!p.startsWith(baseDir)) {
            throw new IllegalArgumentException("Path is outside the project: " + relative);
        }
        return p;
    }

    static String className(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.(groovy|gsh|gy)$", "");
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.length() == 0 || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, "Script_");
        }
        return sb.toString();
    }

    private static Method findFunction(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    private MessageSupport newMessage(Class<?> declared, Exchange exchange, List<Diagnostic> diags) throws Exception {
        Class<? extends MessageSupport> cls = defaultMessageClass;
        if (MessageSupport.class.isAssignableFrom(declared) && declared != MessageSupport.class) {
            cls = declared.asSubclass(MessageSupport.class);
        }
        if ("v2".equals(apiVersion) && cls.getName().equals("com.sap.gateway.ip.core.customdev.util.Message")) {
            diags.add(Diagnostic.warning("Script API v1 Message used in a Groovy Script 2.x profile. Whether CPI's 2.x step "
                    + "still accepts v1 imports is not verified yet (probe fingerprint-v2.groovy)."));
        }
        return cls.getConstructor(Exchange.class).newInstance(exchange);
    }

    private static Object inputBody(SimExchange in, String type) {
        switch (type) {
            case "byte[]":
                return in.getBody();
            case "InputStream":
                return new ByteArrayInputStream(in.getBody());
            case "String":
            default:
                return in.getBodyAsString();
        }
    }

    // ---------------------------------------------------------------- output

    private static Json.Obj describeOutput(Exchange exchange, Charset charset, List<Diagnostic> diags) {
        Object body = exchange.getIn().getBody();
        String text;
        String bodyClass = body == null ? "null" : body.getClass().getName();
        try {
            if (body == null) {
                text = "";
            } else if (body instanceof String) {
                text = (String) body;
            } else if (body instanceof byte[]) {
                text = new String((byte[]) body, charset);
            } else if (body instanceof InputStream || body instanceof Reader) {
                text = exchange.getIn().getBody(String.class);
            } else {
                text = exchange.getIn().getBody(String.class);
                if (text == null) {
                    text = body.toString();
                }
            }
        } catch (RuntimeException e) {
            text = String.valueOf(body);
            diags.add(Diagnostic.warning("Body of type " + bodyClass + " could not be converted to text: " + e.getMessage()));
        }
        Map<String, Object> headers = new LinkedHashMap<>(exchange.getIn().getHeaders());
        Map<String, Object> props = new LinkedHashMap<>(exchange.getProperties());
        return Json.o()
                .with("body", text == null ? "" : text)
                .with("bodyType", bodyClass)
                .with("encoding", charset.name())
                .with("bodySize", text == null ? 0 : text.getBytes(charset).length)
                .with("headers", TypedValue.writeList(headers))
                .with("properties", TypedValue.writeList(props));
    }

    private Json.Obj finish(Json.Obj result, boolean ok, String failedPhase, List<Diagnostic> diags, Json.Obj output,
                            Json.Obj changes, Platform platform, String console, long t0, double compileMs,
                            boolean cached, MessageSupport message) {
        result.with("ok", ok);
        if (failedPhase != null) {
            result.with("failedPhase", failedPhase);
        }
        List<Object> d = new ArrayList<>();
        diags.forEach(x -> d.add(x.toJson()));
        result.with("diagnostics", d);
        if (output != null) {
            result.with("output", output);
            result.with("changes", changes);
        }
        if (message != null) {
            List<Object> att = new ArrayList<>();
            message.getAttachmentWrapperObjects().forEach((k, v) -> att.add(Json.o().with("name", k)
                    .with("contentType", v.getDataHandler() == null ? null : v.getDataHandler().getContentType())
                    .with("size", v.size())));
            result.with("attachments", att);
        }
        result.with("mpl", platform.mplJson());
        result.with("apiCalls", platform.apiCallsJson());
        result.with("console", console);
        result.with("timing", Json.o().with("compileMs", compileMs).with("cached", cached)
                .with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
        result.with("engine", engineInfo());
        return result;
    }

    public Map<String, Object> engineInfo() {
        return Json.o().with("profile", "v1".equals(apiVersion) ? "groovy-1.x" : "groovy-2.x")
                .with("groovy", GroovySystem.getVersion())
                .with("camel", Exchanges.camelVersion())
                .with("java", System.getProperty("java.version"))
                .with("scriptApi", apiVersion);
    }

    // ---------------------------------------------------------------- diagnostics

    private static void compileDiagnostics(MultipleCompilationErrorsException e, List<Diagnostic> diags) {
        for (Object m : e.getErrorCollector().getErrors()) {
            if (m instanceof SyntaxErrorMessage) {
                SyntaxException se = ((SyntaxErrorMessage) m).getCause();
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, se.getOriginalMessage(), null, null,
                        se.getLine(), se.getStartColumn()));
            } else if (m instanceof ExceptionMessage) {
                Exception cause = ((ExceptionMessage) m).getCause();
                diags.add(Diagnostic.error(cause == null ? m.toString() : cause.toString()));
            } else {
                diags.add(Diagnostic.error(String.valueOf(m)));
            }
        }
        if (diags.isEmpty()) {
            diags.add(Diagnostic.error(e.getMessage()));
        }
    }

    private static Diagnostic runtimeDiagnostic(Throwable e, String className) {
        Throwable t = e;
        int line = -1;
        while (t != null && line < 0) {
            line = scriptLine(t, className);
            if (line < 0) {
                t = t.getCause();
            }
        }
        Throwable shown = unwrap(e);
        String msg = shown.getClass().getName() + (shown.getMessage() == null ? "" : ": " + shown.getMessage());
        return new Diagnostic(Diagnostic.Severity.ERROR, msg, null, null, line, -1);
    }

    /** Removes Groovy/Java reflection wrappers so the script's own exception is reported. */
    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t
                && (t.getClass().getName().equals("org.codehaus.groovy.runtime.InvokerInvocationException")
                    || t instanceof java.lang.reflect.InvocationTargetException)) {
            t = t.getCause();
        }
        return t;
    }

    private static int scriptLine(Throwable t, String className) {
        for (StackTraceElement el : t.getStackTrace()) {
            if ((className + ".groovy").equals(el.getFileName()) && el.getLineNumber() > 0) {
                return el.getLineNumber();
            }
        }
        return -1;
    }

    private static String stackTrace(Throwable e, String className) {
        StringBuilder sb = new StringBuilder();
        Throwable first = unwrap(e);
        for (Throwable t = first; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t != first) {
                sb.append("Caused by: ");
            }
            sb.append(t).append('\n');
            int shown = 0;
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith("io.cpiwb.groovy.ScriptRunner")) {
                    break; // everything below is the simulator itself
                }
                boolean script = (className + ".groovy").equals(el.getFileName());
                boolean internal = el.getClassName().startsWith("org.codehaus.groovy.") || el.getClassName().startsWith("groovy.")
                        || el.getClassName().startsWith("java.lang.reflect.") || el.getClassName().startsWith("jdk.internal.")
                        || el.getClassName().startsWith("io.cpiwb.") || el.getClassName().startsWith("sun.reflect.");
                if (script || (!internal && shown < 6)) {
                    sb.append("    at ").append(el).append('\n');
                    shown++;
                }
            }
        }
        return sb.toString();
    }

    private static void lint(String source, List<Diagnostic> diags) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("import ") && l.replace(";", "").endsWith(".*")) {
                diags.add(new Diagnostic(Diagnostic.Severity.INFO, "Wildcard import: SAP recommends explicit imports "
                        + "for Groovy Script 2.x compatibility.", "lint", null, i + 1, 1));
            }
        }
    }

    // ---------------------------------------------------------------- platform data

    /** Platform data from the project's platform/ folder; keys given in the request replace the folder's. */
    private static Map<String, Object> platformData(Map<String, Object> req, Path baseDir) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (baseDir != null) {
            for (String[] f : PLATFORM_FILES) {
                Path file = baseDir.resolve("platform").resolve(f[1]);
                if (Files.isRegularFile(file)) {
                    try {
                        out.put(f[0], Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("platform/" + f[1] + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        Map<String, Object> p = Json.obj(req, "platform");
        if (p != null) {
            out.putAll(p);
        }
        return out;
    }

    private static final String[][] PLATFORM_FILES = {
            {"credentials", "credentials.json"},
            {"valueMappings", "value-mappings.json"},
            {"partnerDirectory", "partner-directory.json"},
            {"keystore", "keystore.json"},
    };

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
