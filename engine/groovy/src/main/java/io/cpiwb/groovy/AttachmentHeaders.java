package io.cpiwb.groovy;

import javax.activation.DataHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIME headers of a message attachment, with the rules of Camel's {@code DefaultAttachment} (checked
 * against camel-core 2.24.3): names are case-insensitive and keep the spelling of the last write,
 * {@code addHeader} collects several values, {@code getHeader} joins them with commas.
 */
public final class AttachmentHeaders {

    private record Entry(String name, List<String> values) { }

    private final Map<String, Entry> headers = new LinkedHashMap<>();

    public String get(String name) {
        Entry e = headers.get(key(name));
        return e == null ? null : String.join(",", e.values());
    }

    public List<String> getAsList(String name) {
        Entry e = headers.get(key(name));
        return e == null ? null : new ArrayList<>(e.values());
    }

    public void add(String name, String value) {
        Entry e = headers.remove(key(name));
        List<String> values = e == null ? new ArrayList<>() : e.values();
        values.add(value);
        headers.put(key(name), new Entry(name, values));
    }

    public void set(String name, String value) {
        headers.remove(key(name));
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(key(name), new Entry(name, values));
    }

    public void remove(String name) {
        headers.remove(key(name));
    }

    public void clear() {
        headers.clear();
    }

    public Collection<String> names() {
        List<String> out = new ArrayList<>();
        headers.values().forEach(e -> out.add(e.name()));
        return out;
    }

    private static String key(String name) {
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Size of an attachment's content in bytes (0 when it cannot be read). */
    public static long size(DataHandler dh) {
        if (dh == null) {
            return 0;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            dh.writeTo(out);
            return out.size();
        } catch (IOException e) {
            return 0;
        }
    }
}
