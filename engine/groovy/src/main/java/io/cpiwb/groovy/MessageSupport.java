package io.cpiwb.groovy;

import org.apache.camel.Exchange;

import javax.activation.DataHandler;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the CPI script {@code Message} on top of a real Apache Camel exchange, so that
 * {@code getBody(Class)}, {@code getHeader(String, Class)} and header name handling behave like on
 * Cloud Integration (Camel type converters, case-insensitive header names).
 * The public API classes for Script API v1/v2 extend this class; it holds only the methods both
 * published Message interfaces share. {@code A} is the API's attachment type.
 */
public abstract class MessageSupport<A> {

    private final Exchange exchange;
    private final Map<String, A> attachments = new LinkedHashMap<>();
    private final long created = System.currentTimeMillis();

    protected MessageSupport(Exchange exchange) {
        this.exchange = exchange;
    }

    /** The underlying Camel exchange (not part of SAP's API). */
    public Exchange getExchangeForSimulator() {
        return exchange;
    }

    protected long createdForSimulator() {
        return created;
    }

    /** Wraps content into the API's attachment type. */
    protected abstract A attachment(DataHandler content);

    /** The content of an attachment of the API's type. */
    protected abstract DataHandler content(A attachment);

    /** The attachments by name; the map is live, as the script may change it. */
    protected Map<String, A> attachmentMap() {
        return attachments;
    }

    public Object getBody() {
        return exchange.getIn().getBody();
    }

    public <T> T getBody(Class<T> type) {
        return exchange.getIn().getBody(type);
    }

    public void setBody(Object body) {
        exchange.getIn().setBody(body);
    }

    public long getBodySize() {
        byte[] b = exchange.getIn().getBody(byte[].class);
        return b == null ? 0 : b.length;
    }

    public Map<String, Object> getHeaders() {
        return exchange.getIn().getHeaders();
    }

    public <T> T getHeader(String name, Class<T> type) {
        return exchange.getIn().getHeader(name, type);
    }

    public void setHeader(String name, Object value) {
        exchange.getIn().setHeader(name, value);
    }

    public void setHeaders(Map<String, Object> headers) {
        exchange.getIn().setHeaders(headers);
    }

    public Map<String, Object> getProperties() {
        return exchange.getProperties();
    }

    public Object getProperty(String name) {
        return exchange.getProperty(name);
    }

    public void setProperty(String name, Object value) {
        exchange.setProperty(name, value);
    }

    public void setProperties(Map<String, Object> properties) {
        exchange.getProperties().clear();
        if (properties != null) {
            exchange.getProperties().putAll(properties);
        }
    }

    /** The attachments' contents by name; writes to this map add or replace attachments. */
    public Map<String, DataHandler> getAttachments() {
        return new AbstractMap<>() {
            @Override
            public DataHandler put(String key, DataHandler value) {
                A old = attachments.put(key, attachment(value));
                return old == null ? null : content(old);
            }

            @Override
            public Set<Entry<String, DataHandler>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public int size() {
                        return attachments.size();
                    }

                    @Override
                    public Iterator<Entry<String, DataHandler>> iterator() {
                        Iterator<Entry<String, A>> it = attachments.entrySet().iterator();
                        return new Iterator<>() {
                            public boolean hasNext() { return it.hasNext(); }
                            public Entry<String, DataHandler> next() {
                                Entry<String, A> e = it.next();
                                return new SimpleImmutableEntry<>(e.getKey(), content(e.getValue()));
                            }
                            public void remove() { it.remove(); }
                        };
                    }
                };
            }
        };
    }

    public void setAttachments(Map<String, DataHandler> map) {
        attachments.clear();
        if (map != null) {
            map.forEach((k, v) -> attachments.put(k, attachment(v)));
        }
    }

    public long getAttachmentsSize() {
        long size = 0;
        for (A a : attachments.values()) {
            size += AttachmentHeaders.size(content(a));
        }
        return size;
    }

    @Override
    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this));
    }
}
