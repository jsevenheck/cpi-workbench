package com.sap.gateway.ip.core.customdev.util;

import io.cpiwb.groovy.MessageSupport;
import org.apache.camel.Exchange;
import org.apache.camel.attachment.Attachment;

import javax.activation.DataHandler;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Script API v1 message ({@code com.sap.gateway.ip.core.customdev.util.Message}), a clean-room
 * implementation of the methods of SAP's published interface ({@code com.sap.cloud.script:script.api}
 * 2.16.x), including the overloads that take Camel 3's {@code Attachment}, which a tenant's message class
 * has (docs/plan.md F49). Not offered: {@code getExtensionFunctions}, whose v1 type is not published.
 */
public class Message extends MessageSupport<AttachmentWrapper> {

    public Message(Exchange exchange) {
        super(exchange);
    }

    @Override
    protected AttachmentWrapper attachment(DataHandler content) {
        return new AttachmentWrapper(content);
    }

    @Override
    protected DataHandler content(AttachmentWrapper attachment) {
        return attachment == null ? null : attachment.getDataHandler();
    }

    public void addAttachmentHeader(String name, String value, AttachmentWrapper attachment) {
        attachment.addHeader(name, value);
    }

    public void setAttachmentHeader(String name, String value, AttachmentWrapper attachment) {
        attachment.setHeader(name, value);
    }

    public String getAttachmentHeader(String name, AttachmentWrapper attachment) {
        return attachment.getHeader(name);
    }

    public void removeAttachmentHeader(String name, AttachmentWrapper attachment) {
        attachment.removeHeader(name);
    }

    public Map<String, AttachmentWrapper> getAttachmentWrapperObjects() {
        return attachmentMap();
    }

    public void setAttachmentWrapperObjects(Map<String, AttachmentWrapper> map) {
        attachmentMap().clear();
        if (map != null) {
            attachmentMap().putAll(map);
        }
    }

    public void addAttachmentObject(String id, AttachmentWrapper content) {
        attachmentMap().put(id, content);
    }

    // The overloads with Camel's Attachment. The attachments are one map; a Camel Attachment that is no
    // AttachmentWrapper is copied into one with its headers (an assumption: how SAP's class stores it is
    // not published).

    public void addAttachmentHeader(String name, String value, Attachment attachment) {
        attachment.addHeader(name, value);
    }

    public void setAttachmentHeader(String name, String value, Attachment attachment) {
        attachment.setHeader(name, value);
    }

    public String getAttachmentHeader(String name, Attachment attachment) {
        return attachment.getHeader(name);
    }

    public void removeAttachmentHeader(String name, Attachment attachment) {
        attachment.removeHeader(name);
    }

    /**
     * The attachments as Camel attachments: a live view of the same map. A Camel attachment put into it is
     * stored as an {@code AttachmentWrapper}; entries cannot be replaced through {@code Entry.setValue}.
     */
    public Map<String, Attachment> getAttachmentObjects() {
        Map<String, AttachmentWrapper> m = attachmentMap();
        return new AbstractMap<>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Set<Entry<String, Attachment>> entrySet() {
                return (Set) Collections.unmodifiableMap(m).entrySet();
            }

            @Override
            public Attachment get(Object key) {
                return m.get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                return m.containsKey(key);
            }

            @Override
            public Attachment put(String key, Attachment value) {
                return m.put(key, wrap(value));
            }

            @Override
            public Attachment remove(Object key) {
                return m.remove(key);
            }

            @Override
            public void clear() {
                m.clear();
            }
        };
    }

    public void setAttachmentObjects(Map<String, Attachment> map) {
        attachmentMap().clear();
        if (map != null) {
            map.forEach(this::addAttachmentObject);
        }
    }

    public void addAttachmentObject(String id, Attachment content) {
        attachmentMap().put(id, wrap(content));
    }

    private static AttachmentWrapper wrap(Attachment a) {
        if (a == null || a instanceof AttachmentWrapper) {
            return (AttachmentWrapper) a;
        }
        AttachmentWrapper w = new AttachmentWrapper(a.getDataHandler());
        if (a.getHeaderNames() != null) {
            for (String n : a.getHeaderNames()) {
                List<String> values = a.getHeaderAsList(n);
                if (values != null) {
                    values.forEach(v -> w.addHeader(n, v));
                }
            }
        }
        return w;
    }
}
