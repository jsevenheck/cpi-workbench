package io.cpiwb.groovy;

import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;
import io.cpiwb.core.SimExchange;
import io.cpiwb.core.TypedValue;
import io.cpiwb.groovy.camel.Exchanges;
import org.apache.camel.Exchange;
import org.apache.camel.support.ExchangeHelper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Content Modifier step (SAP: "Define Content Modifier"), on the engine's Camel exchange. The rows use
 * the iFlow's own encoding (as in exported {@code .iflw} files, {@code headerTable} / {@code propertyTable}):
 * <ul>
 * <li>{@code action} Create or Delete, {@code type} constant, header, property, xpath, expression,
 * persisted variables, global persisted variables, numberRange or external parameter.</li>
 * <li>A Delete row names {@code $name=X}, or {@code $include=pattern,$exclude=pattern}; Camel's
 * {@code removeHeaders} / {@code removeProperties} apply the patterns. That CPI uses them is an assumption.</li>
 * <li>XPath rows arrive evaluated ({@code resolved}): CPI evaluates them with XPath 3.1 (SAP), which runs in
 * the XML engine.</li>
 * </ul>
 * Rows run top to bottom (SAP), the header table before the property table and both before the body (an
 * assumption from a SAP Community blog).
 */
public final class ContentModifierStep {

    // Keep a live Camel exchange between batches: serializing and reloading the headers could change their
    // Java types, and a new exchange would generate a different exchange ID. Tokens are single-use.
    private static final int MAX_PAUSED = 8;
    private static final long PAUSE_NANOS = Duration.ofMinutes(1).toNanos();
    private final Map<String, State> paused = new HashMap<>();
    private final ScheduledThreadPoolExecutor expiry = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "cpiwb-content-modifier-expiry");
        thread.setDaemon(true);
        return thread;
    });

    public ContentModifierStep() {
        expiry.setRemoveOnCancelPolicy(true);
    }

    private static final class State {
        final Path project;
        final Map<String, Object> inputHeaders;
        final Map<String, Object> inputProperties;
        final Exchange exchange;
        final List<Diagnostic> diags = new ArrayList<>();
        final List<Object> trace = new ArrayList<>();
        long elapsed;
        long pausedAt;
        ScheduledFuture<?> expiry;

        State(Path project, Map<String, Object> input) {
            SimExchange message = SimExchange.fromJson(input);
            this.project = project;
            this.inputHeaders = message.getHeaders();
            this.inputProperties = message.getProperties();
            this.exchange = SimpleStep.exchange(message, input != null && input.get("bodyBase64") != null);
            Diagnostic dropped = SimpleStep.nullProperties(message);
            if (dropped != null) {
                diags.add(dropped);
            }
        }
    }

    private State resume(Map<String, Object> req, Path project) {
        String token = Json.str(req, "continuation", null);
        if (token == null) {
            return new State(project, Json.obj(req, "input"));
        }
        State state = paused.get(token);
        if (state == null || !Objects.equals(state.project, project)) {
            throw new IllegalArgumentException("Content Modifier continuation is unavailable; run the step again.");
        }
        paused.remove(token);
        state.expiry.cancel(false);
        if (System.nanoTime() - state.pausedAt >= PAUSE_NANOS) {
            throw new IllegalArgumentException("Content Modifier continuation expired; run the step again.");
        }
        return state;
    }

    private synchronized void expire(String token) {
        paused.remove(token);
    }

    /** Applies a batch before XPath. Only the values needed as XPath variables leave the engine. */
    public synchronized Map<String, Object> advance(Map<String, Object> req, Path project) {
        State state = resume(req, project);
        if (paused.size() >= MAX_PAUSED) {
            throw new IllegalStateException("UnsupportedInSimulator: too many paused Content Modifier runs; retry when they finish.");
        }
        if (!applyBatch(state, req, false)) {
            return finish(state, false);
        }
        String token = UUID.randomUUID().toString();
        Json.Obj result = Json.o().with("ok", true).with("continuation", token);
        if (Json.bool(req, "variables", false)) {
            result.with("variables", Json.o().with("headers", TypedValue.writeList(state.exchange.getIn().getHeaders()))
                    .with("properties", TypedValue.writeList(state.exchange.getProperties())));
        }
        state.pausedAt = System.nanoTime();
        paused.put(token, state);
        state.expiry = expiry.schedule(() -> expire(token), PAUSE_NANOS, TimeUnit.NANOSECONDS);
        return result;
    }

    /** Releases a paused exchange when XPath fails or the caller abandons the run. */
    public synchronized Map<String, Object> discard(Map<String, Object> req, Path project) {
        String token = Json.str(req, "continuation", "");
        State state = paused.get(token);
        boolean discarded = state != null && Objects.equals(state.project, project);
        if (discarded) {
            paused.remove(token);
            state.expiry.cancel(false);
        }
        return Json.o().with("discarded", discarded);
    }

    /** Final batch and body, or an entire step when no XPath barrier was needed. */
    public synchronized Map<String, Object> run(Map<String, Object> req, Path project) {
        State state = resume(req, project);
        return finish(state, applyBatch(state, req, true));
    }

    private static boolean applyBatch(State state, Map<String, Object> req, boolean last) {
        long t0 = System.nanoTime();
        try {
            for (String table : List.of("headers", "properties")) {
                for (Object o : Json.arr(req, table)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) o;
                    state.trace.add(apply(state.exchange, "headers".equals(table), row, state.diags));
                }
            }
            String body = last ? Json.str(req, "body", "") : "";
            if (!body.isEmpty()) {
                Object value = "expression".equalsIgnoreCase(Json.str(req, "bodyType", "constant"))
                        ? simple(state.exchange, body, String.class) : body;
                warnIfReplaced(state.exchange, value, "Message Body", state.diags);
                state.exchange.getIn().setBody(value);
                state.trace.add(Json.o().with("table", "body").with("name", "Message Body").with("value", TypedValue.display(value)));
            }
            return true;
        } catch (RuntimeException e) {
            state.diags.add(Diagnostic.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return false;
        } finally {
            state.elapsed += System.nanoTime() - t0;
        }
    }

    private static Json.Obj finish(State state, boolean ok) {
        Json.Obj result = Json.o().with("ok", ok).with("trace", state.trace);
        if (ok) {
            Object body = state.exchange.getIn().getBody();
            // Do not use the script runner's text conversion for a Content Modifier carrying bytes.
            Json.Obj output = body instanceof byte[] bytes
                    ? new SimExchange(bytes, StandardCharsets.UTF_8, state.exchange.getIn().getHeaders(),
                            state.exchange.getProperties()).toJsonBinary().with("bodyType", bytes.getClass().getName())
                    : ScriptRunner.describeOutput(state.exchange, StandardCharsets.UTF_8, state.diags);
            result.with("output", output).with("changes", Json.o()
                            .with("headers", SimExchange.diff(state.inputHeaders, state.exchange.getIn().getHeaders()))
                            .with("properties", SimExchange.diff(state.inputProperties, state.exchange.getProperties())));
        }
        List<Object> d = new ArrayList<>();
        state.diags.forEach(x -> d.add(x.toJson()));
        return result.with("diagnostics", d)
                .with("engine", "Camel " + Exchanges.camelVersion())
                .with("timing", Json.o().with("totalMs", state.elapsed / 1_000_000.0));
    }

    private static Map<String, Object> apply(Exchange exchange, boolean header, Map<String, Object> row, List<Diagnostic> diags) {
        String name = Json.str(row, "name", "").trim();
        String type = Json.str(row, "type", "constant").trim().toLowerCase();
        String value = Json.str(row, "value", "");
        String where = (header ? "Header " : "Property ") + name;
        Json.Obj step = Json.o().with("table", header ? "header" : "property").with("name", name);
        if ("delete".equalsIgnoreCase(Json.str(row, "action", "Create"))) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException((header ? "A header" : "A property") + " Delete row has no name or pattern.");
            }
            delete(exchange, header, name);
            return step.with("deleted", true);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException((header ? "A header" : "A property") + " row has no name.");
        }
        String datatype = Json.str(row, "datatype", "").trim();
        Object v;
        switch (type) {
            case "constant":
                v = value;
                if (value.contains("{{")) {
                    diags.add(Diagnostic.warning(where + ": the externalized parameter in '" + value + "' is not resolved "
                            + "(no value in the iFlow's parameters.prop)."));
                }
                break;
            case "header":
                v = exchange.getIn().getHeader(value);
                break;
            case "property":
                v = exchange.getProperty(value);
                break;
            case "expression":
                v = simple(exchange, value, datatype.isEmpty() ? Object.class : type(exchange, datatype, where));
                warnIfReplaced(exchange, v, where, diags);
                break;
            case "xpath":
                if (!row.containsKey("resolved")) {
                    throw new IllegalArgumentException(where + ": the XPath was not evaluated (it needs the XML engine).");
                }
                Object resolved = row.get("resolved");
                if (datatype.startsWith("org.w3c.dom.")) {
                    v = resolved;
                    diags.add(Diagnostic.info(where + ": " + datatype + " is kept as XML text in the simulator."));
                } else {
                    v = resolved == null ? null : convert(exchange, datatype.isEmpty() ? String.class : type(exchange, datatype, where), resolved);
                }
                break;
            case "persisted variables":
            case "global persisted variables":
                v = Json.str(row, "default", "");
                diags.add(Diagnostic.info(where + ": the simulator has no " + ("global persisted variables".equals(type) ? "global" : "local")
                        + " variables, so the default value is used (CPI does so when the variable is not found)."));
                break;
            default:
                throw new IllegalArgumentException("UnsupportedInSimulator: " + where + " uses the source type '" + type
                        + "', which the simulator does not provide.");
        }
        if (header) {
            exchange.getIn().setHeader(name, v);
        } else {
            exchange.setProperty(name, v);
        }
        return step.with("value", TypedValue.display(v)).with("type", TypedValue.typeOf(v));
    }

    /** {@code $name=X}, or {@code $include=a*,$exclude=b*}; a plain text is a name or a Camel pattern. */
    private static void delete(Exchange exchange, boolean header, String spec) {
        String include = null;
        List<String> exclude = new ArrayList<>();
        for (String part : spec.split(",(?=\\$)")) {
            String p = part.trim();
            if (p.startsWith("$name=")) {
                String n = p.substring(6);
                if (header) {
                    exchange.getIn().removeHeader(n);
                } else {
                    exchange.removeProperty(n);
                }
            } else if (p.startsWith("$include=")) {
                include = p.substring(9);
            } else if (p.startsWith("$exclude=")) {
                exclude.add(p.substring(9));
            } else if (!p.isEmpty()) {
                include = p;
            }
        }
        if (include != null) {
            String[] ex = exclude.toArray(new String[0]);
            if (header) {
                exchange.getIn().removeHeaders(include, ex);
            } else {
                exchange.removeProperties(include, ex);
            }
        }
    }

    /**
     * Camel turns a byte body into text with its charset ({@code CamelCharsetName}, else UTF-8) and replaces bytes
     * that are no text with U+FFFD. That CPI does the same is an assumption (same Camel conversion); the simulator
     * says so instead of handing on the replaced text silently.
     */
    static void warnIfReplaced(Exchange exchange, Object value, String where, List<Diagnostic> diags) {
        if (!(value instanceof String text) || text.indexOf(0xFFFD) < 0
                || !(exchange.getIn().getBody() instanceof byte[] bytes)) {
            return;
        }
        String name = ExchangeHelper.getCharsetName(exchange, true);
        Charset charset;
        try {
            charset = Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            diags.add(Diagnostic.warning(where + ": the binary body is not valid " + charset.name() + " text; "
                    + "reading it as text replaced the bytes that are no text with U+FFFD, so the value is not the "
                    + "body's bytes." + ("Message Body".equals(where) ? "" : " Leave the Data Type empty to keep byte[].")));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object simple(Exchange exchange, String expr, Class<?> type) {
        return exchange.getContext().resolveLanguage("simple").createExpression(SimpleStep.toCamel(expr, false))
                .evaluate(exchange, (Class<Object>) type);
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Exchange exchange, Class<?> type, Object value) {
        try {
            return exchange.getContext().getTypeConverter().mandatoryConvertTo((Class<Object>) type, exchange, value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /** The Data Type column, resolved by Camel (so {@code String} and {@code Integer} also work). */
    private static Class<?> type(Exchange exchange, String name, String where) {
        Class<?> c = exchange.getContext().getClassResolver().resolveClass(name);
        if (c == null) {
            throw new IllegalArgumentException(where + ": data type '" + name + "' is not a class Camel can load.");
        }
        return c;
    }
}
