package com.sap.it.script.v2.api;

import com.sap.it.script.v2.api.extensions.ExchangeExtensionFunctions;
import com.sap.it.script.v2.api.extensions.ExtensionFunctions;
import com.sap.it.script.v2.api.extensions.ProcessDirectService;
import com.sap.it.script.v2.api.attachment.DefaultAttachment;
import io.cpiwb.groovy.MessageSupport;
import org.apache.camel.Exchange;
import org.apache.camel.StreamCache;

import javax.activation.DataHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Script API v2 message ({@code com.sap.it.script.v2.api.Message}), a clean-room implementation of
 * the methods of SAP's published interface ({@code com.sap.cloud.script:script.v2.api} 4.2.2). Methods
 * of the v1 message that the v2 interface lacks (SOAP header list, {@code AttachmentWrapper}) are not
 * offered; whether CPI's runtime class still has them is open (docs/plan.md F6).
 */
public class Message extends MessageSupport<Attachment> {

    public Message(Exchange exchange) {
        super(exchange);
    }

    @Override
    protected Attachment attachment(DataHandler content) {
        return new DefaultAttachment(content);
    }

    @Override
    protected DataHandler content(Attachment attachment) {
        return attachment == null ? null : attachment.getDataHandler();
    }

    public Object getHeader(String name) {
        return getExchangeForSimulator().getIn().getHeader(name);
    }

    public Object getHeader(String name, Object defaultValue) {
        return getExchangeForSimulator().getIn().getHeader(name, defaultValue);
    }

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

    public Map<String, Attachment> getAttachmentObjects() {
        return attachmentMap();
    }

    public void setAttachmentObjects(Map<String, Attachment> map) {
        attachmentMap().clear();
        if (map != null) {
            attachmentMap().putAll(map);
        }
    }

    public void addAttachmentObject(String id, Attachment attachment) {
        attachmentMap().put(id, attachment);
    }

    public void clearAttachments() {
        attachmentMap().clear();
    }

    /** The simulator receives no SOAP headers on a 2.x message, so there is nothing to clear. */
    public void clearSoapHeaders() {
    }

    /** Rewinds a stream body so it can be read again. */
    public void resetBodyStream() {
        Object body = getBody();
        try {
            if (body instanceof StreamCache) {
                ((StreamCache) body).reset();
            } else if (body instanceof InputStream && ((InputStream) body).markSupported()) {
                ((InputStream) body).reset();
            }
        } catch (IOException e) {
            throw new IllegalStateException("The body stream cannot be reset: " + e.getMessage(), e);
        }
    }

    public ExtensionFunctions getExtensionFunctions(Class<? extends ExtensionFunctions> type) {
        if (type == ExchangeExtensionFunctions.class) {
            long created = createdForSimulator();
            return new ExchangeExtensionFunctions() {
                public long getCreated() { return created; }
                public long getExchangeCreated() { return created; }
            };
        }
        if (type == ProcessDirectService.class) {
            return (ProcessDirectService) (address, content) -> {
                throw new UnsupportedOperationException("UnsupportedInSimulator: ProcessDirectService.invokeProcessDirect("
                        + address + ") calls another integration flow, which the simulator does not run.");
            };
        }
        throw new IllegalArgumentException("Unknown extension functions type: " + type);
    }
}
