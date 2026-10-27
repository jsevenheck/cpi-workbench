package io.cpiwb.groovy;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import io.cpiwb.core.Diagnostic;
import io.cpiwb.core.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JSONPath tester on Jayway JsonPath, the library behind Camel's {@code jsonpath} language and the
 * {@code json-path} jar a script can add as a resource. No standard CPI step is known to evaluate JSONPath,
 * so this is a tool for expressions used in scripts, not a simulated step. The jar is relocated
 * (pom.xml), so scripts do not see it on the engine's classpath.
 */
public final class JsonPathStep {

    /** The json-path version in groovy-v1/pom.xml and groovy-v2/pom.xml (the shaded jar keeps no manifest of it). */
    static final String VERSION = "2.10.0";

    private static final Configuration JSON = Configuration.defaultConfiguration();
    private static final Configuration PATHS = JSON.addOptions(Option.AS_PATH_LIST);

    /**
     * Request: {@code {"expression": "$.items[*].sku", "input": {"body": "..."}}}. Result: one item per
     * match with its normalized path, the value as JSON and its Java type, as {@code JsonPath.read}
     * returns it with the default (json-smart) provider.
     */
    public Map<String, Object> run(Map<String, Object> req) {
        long t0 = System.nanoTime();
        String expr = Json.str(req, "expression", "");
        Map<String, Object> in = Json.obj(req, "input");
        String body = in == null ? "" : Json.str(in, "body", "");
        List<Object> items = new ArrayList<>();
        List<Object> diags = new ArrayList<>();
        Json.Obj result = Json.o();
        boolean ok = true;
        try {
            JsonPath path = JsonPath.compile(expr);
            result.with("definite", path.isDefinite());
            // json-smart reads an empty text as "", but JsonPath.read(String, ...) rejects it.
            if (body.isBlank()) {
                throw new IllegalArgumentException("json string can not be null or empty");
            }
            Object doc = JSON.jsonProvider().parse(body);
            Object value = path.read(doc, JSON);
            List<String> paths = paths(path, doc);
            // An indefinite path returns a list of its matches; a definite path or a function one value.
            List<?> values = paths != null && !path.isDefinite() ? (List<?>) value : Collections.singletonList(value);
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                items.add(Json.o()
                        .with("path", paths != null && i < paths.size() ? paths.get(i) : null)
                        .with("value", Json.write(v))
                        .with("type", type(v)));
            }
        } catch (PathNotFoundException e) {
            diags.add(Diagnostic.info(e.getMessage() + " (JsonPath.read throws PathNotFoundException)").toJson());
        } catch (InvalidPathException | InvalidJsonException e) {
            ok = false;
            diags.add(Diagnostic.error(e.getMessage()).toJson());
        } catch (RuntimeException e) {
            ok = false;
            diags.add(Diagnostic.error(e.getClass().getSimpleName() + ": " + e.getMessage()).toJson());
        }
        return result.with("ok", ok)
                .with("items", items)
                .with("count", items.size())
                .with("diagnostics", diags)
                .with("engine", "Jayway JsonPath " + VERSION)
                .with("timing", Json.o().with("totalMs", (System.nanoTime() - t0) / 1_000_000.0));
    }

    /** Map and List for objects and arrays (json-smart's JSONObject and JSONArray), else the Java class. */
    private static String type(Object v) {
        return v == null ? "null" : v instanceof Map ? "Map" : v instanceof List ? "List" : v.getClass().getSimpleName();
    }

    /** The normalized path of each match; null for a function such as length(), which has none. */
    @SuppressWarnings("unchecked")
    private static List<String> paths(JsonPath path, Object doc) {
        try {
            return (List<String>) path.read(doc, PATHS);
        } catch (PathNotFoundException e) {
            return List.of();
        } catch (JsonPathException e) {
            return null;
        }
    }
}
