package io.cpiwb.groovy;

import io.cpiwb.core.Json;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of the current script run that the simulated SAP APIs read and write: mocked platform data
 * (secure store, value mappings), the message processing log and a log of API calls. The engine runs
 * one script at a time, so a single current instance is enough.
 */
public final class Platform {

    private static volatile Platform current = new Platform(Map.of(), List.of(), true);

    /** alias → {username, password, properties} */
    private final Map<String, Map<String, Object>> credentials;
    private final List<Map<String, Object>> valueMappings;
    private final boolean mplEnabled;

    private final List<Object> apiCalls = new ArrayList<>();
    private final List<Object> mplProperties = new ArrayList<>();
    private final List<Object> mplCustomHeaders = new ArrayList<>();
    private final List<Object> mplAttachments = new ArrayList<>();

    public Platform(Map<String, Map<String, Object>> credentials, List<Map<String, Object>> valueMappings,
                    boolean mplEnabled) {
        this.credentials = credentials;
        this.valueMappings = valueMappings;
        this.mplEnabled = mplEnabled;
    }

    public static Platform current() {
        return current;
    }

    static void setCurrent(Platform p) {
        current = p;
    }

    public boolean isMplEnabled() {
        return mplEnabled;
    }

    public void recordCall(String api, String method, Object... args) {
        List<Object> a = new ArrayList<>();
        for (Object o : args) {
            a.add(o == null ? null : o.toString());
        }
        apiCalls.add(Json.o().with("api", api).with("method", method).with("args", a));
    }

    // ---------------------------------------------------------------- secure store

    public Map<String, Object> credential(String alias) {
        recordCall("SecureStoreService", "getUserCredential", alias);
        return alias == null ? null : credentials.get(alias);
    }

    // ---------------------------------------------------------------- value mapping

    public String mappedValue(String sa, String si, String sv, String ta, String ti) {
        recordCall("ValueMappingApi", "getMappedValue", sa, si, sv, ta, ti);
        for (Map<String, Object> m : valueMappings) {
            if (eq(m, "sourceAgency", sa) && eq(m, "sourceIdentifier", si) && eq(m, "sourceValue", sv)
                    && eq(m, "targetAgency", ta) && eq(m, "targetIdentifier", ti)) {
                return Json.str(m, "targetValue", null);
            }
        }
        return null;
    }

    private static boolean eq(Map<String, Object> m, String key, String value) {
        return value != null && value.equals(Json.str(m, key, null));
    }

    // ---------------------------------------------------------------- message processing log

    public void mplProperty(String type, String name, Object value) {
        mplProperties.add(Json.o().with("name", name).with("type", type)
                .with("value", value instanceof Date ? ((Date) value).toInstant().toString() : value == null ? null : value.toString()));
    }

    public void mplCustomHeader(String name, String value) {
        mplCustomHeaders.add(Json.o().with("name", name).with("value", value));
    }

    public void mplAttachment(String name, String content, String mediaType) {
        mplAttachments.add(Json.o().with("name", name).with("mediaType", mediaType).with("content", content));
    }

    public Map<String, Object> mplJson() {
        return Json.o().with("enabled", mplEnabled).with("properties", mplProperties)
                .with("customHeaders", mplCustomHeaders).with("attachments", mplAttachments);
    }

    public List<Object> apiCallsJson() {
        return apiCalls;
    }

    /** Reads the {@code platform} object of a request (credentials, valueMappings, mpl). */
    @SuppressWarnings("unchecked")
    static Platform fromJson(Map<String, Object> p) {
        Map<String, Map<String, Object>> creds = new LinkedHashMap<>();
        Object c = p == null ? null : p.get("credentials");
        if (c instanceof Map) {
            ((Map<String, Object>) c).forEach((k, v) -> {
                if (v instanceof Map) {
                    creds.put(k, (Map<String, Object>) v);
                }
            });
        } else if (c instanceof List) {
            for (Object o : (List<Object>) c) {
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    creds.put(Json.str(m, "alias", Json.str(m, "name", "")), m);
                }
            }
        }
        List<Map<String, Object>> vms = new ArrayList<>();
        for (Object o : Json.arr(p, "valueMappings")) {
            if (o instanceof Map) {
                vms.add((Map<String, Object>) o);
            }
        }
        return new Platform(creds, vms, Json.bool(p, "mplEnabled", true));
    }
}
