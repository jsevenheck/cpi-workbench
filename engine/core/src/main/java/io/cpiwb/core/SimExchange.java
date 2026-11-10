package io.cpiwb.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The simulated CPI message: body, headers and exchange properties. Header and property values are
 * real Java objects, as in the Camel exchange CPI runs on.
 */
public final class SimExchange {

    private byte[] body;
    private Charset charset;
    private final LinkedHashMap<String, Object> headers;
    private final LinkedHashMap<String, Object> properties;

    public SimExchange(byte[] body, Charset charset,
                       Map<String, Object> headers, Map<String, Object> properties) {
        this.body = body == null ? new byte[0] : body;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        this.headers = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        this.properties = new LinkedHashMap<>(properties == null ? Map.of() : properties);
    }

    /**
     * Reads the {@code input} object of an RPC request:
     * {@code {"body": "...", "bodyBase64": "...", "encoding": "UTF-8", "headers": [...], "properties": [...]}}.
     */
    public static SimExchange fromJson(Map<String, Object> input) {
        if (input == null) {
            input = Map.of();
        }
        String text = input.get("bodyBase64") != null ? null : Json.str(input, "body", "");
        // A body given as text takes the encoding its XML declaration names: a parser reads the bytes in that
        // encoding (XML 1.0, 4.3.3). That such a message arrives on a tenant in it is an assumption.
        Charset declared = input.get("encoding") == null ? TextFiles.declaredCharset(text) : null;
        Charset cs = declared != null ? declared : Charset.forName(Json.str(input, "encoding", "UTF-8"));
        if (declared != null && !declared.newEncoder().canEncode(text)) {
            // Encoding it anyway would turn the character into "?" without a word.
            throw new IllegalArgumentException("The body has a character that the encoding its XML declaration names ("
                    + declared.name() + ") cannot hold" + firstUnmappable(text, declared)
                    + ". Write it as a character reference (&#x...;) or change the declaration.");
        }
        byte[] body = text == null ? Base64.getDecoder().decode(Json.str(input, "bodyBase64", "")) : text.getBytes(cs);
        return new SimExchange(body, cs,
                TypedValue.readMap(input.get("headers")),
                TypedValue.readMap(input.get("properties")));
    }

    private static String firstUnmappable(String text, Charset cs) {
        java.nio.charset.CharsetEncoder enc = cs.newEncoder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (!enc.canEncode(ch)) {
                long line = text.substring(0, i).chars().filter(c -> c == '\n').count() + 1;
                return ": '" + ch + "' in line " + line;
            }
            i += ch.length();
        }
        return "";
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body, Charset charset) {
        this.body = body == null ? new byte[0] : body;
        if (charset != null) {
            this.charset = charset;
        }
    }

    public Charset getCharset() {
        return charset;
    }

    public String getBodyAsString() {
        return new String(body, charset);
    }

    public LinkedHashMap<String, Object> getHeaders() {
        return headers;
    }

    public LinkedHashMap<String, Object> getProperties() {
        return properties;
    }

    public SimExchange copy() {
        return new SimExchange(body.clone(), charset, headers, properties);
    }

    /** Message as JSON for the UI: body as text, headers/properties typed. */
    public Json.Obj toJson() {
        return Json.o()
                .with("body", getBodyAsString())
                .with("encoding", charset.name())
                .with("bodySize", body.length)
                .with("headers", TypedValue.writeList(headers))
                .with("properties", TypedValue.writeList(properties));
    }

    /** Added / changed / removed entries between two maps (values compared with type). */
    public static Json.Obj diff(Map<String, Object> before, Map<String, Object> after) {
        List<Object> added = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                added.add(e.getKey());
            } else if (!same(before.get(e.getKey()), e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : before.keySet()) {
            if (!after.containsKey(k)) {
                removed.add(k);
            }
        }
        return Json.o().with("added", added).with("changed", changed).with("removed", removed);
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof byte[] && b instanceof byte[]) {
            return Arrays.equals((byte[]) a, (byte[]) b);
        }
        return Objects.equals(a, b) && Objects.equals(TypedValue.typeOf(a), TypedValue.typeOf(b));
    }
}
