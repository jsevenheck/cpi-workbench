package io.cpiwb.groovy;

import com.sap.gateway.ip.core.customdev.util.AttachmentWrapper;
import org.apache.camel.Exchange;

import javax.activation.DataHandler;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementation of the CPI script {@code Message} on top of a real Apache Camel exchange, so that
 * {@code getBody(Class)}, {@code getHeader(String, Class)} and header name handling behave like on
 * Cloud Integration (Camel type converters, case-insensitive header names).
 * Public API classes for Script API v1/v2 extend this class.
 */
public abstract class MessageSupport {

    private final Exchange exchange;
    private final Map<String, AttachmentWrapper> attachments = new LinkedHashMap<>();

    protected MessageSupport(Exchange exchange) {
        this.exchange = exchange;
    }

    /** The underlying Camel exchange (not part of SAP's documented API). */
    public Exchange getExchangeForSimulator() {
        return exchange;
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

    public Map<String, DataHandler> getAttachments() {
        Map<String, DataHandler> out = new LinkedHashMap<>();
        attachments.forEach((k, v) -> out.put(k, v.getDataHandler()));
        return out;
    }

    public void setAttachments(Map<String, DataHandler> map) {
        attachments.clear();
        if (map != null) {
            map.forEach((k, v) -> attachments.put(k, new AttachmentWrapper(v)));
        }
    }

    public void addAttachmentObject(String id, AttachmentWrapper content) {
        attachments.put(id, content);
    }

    public Map<String, AttachmentWrapper> getAttachmentWrapperObjects() {
        return attachments;
    }

    public void setAttachmentHeader(String headerName, String headerValue, AttachmentWrapper attachment) {
        attachment.addHeader(headerName, headerValue);
    }

    public long getAttachmentsSize() {
        long size = 0;
        for (AttachmentWrapper a : attachments.values()) {
            size += a.size();
        }
        return size;
    }

    @Override
    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(this));
    }
}
