package com.sap.gateway.ip.core.customdev.util;

import io.cpiwb.groovy.AttachmentHeaders;
import org.apache.camel.attachment.DefaultAttachment;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import java.util.Collection;
import java.util.List;

/**
 * A message attachment with its MIME headers. Constructors and methods follow SAP's published Script
 * API ({@code com.sap.cloud.script:script.api} 2.16.x), whose class extends Camel 3's
 * {@code org.apache.camel.attachment.DefaultAttachment} (docs/plan.md F30), so it is a Camel
 * {@code Attachment} too.
 */
public class AttachmentWrapper extends DefaultAttachment {

    private final AttachmentHeaders headers = new AttachmentHeaders();

    public AttachmentWrapper(DataHandler dataHandler) {
        super(dataHandler);
    }

    public AttachmentWrapper(DataSource dataSource) {
        this(new DataHandler(dataSource));
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public List<String> getHeaderAsList(String name) {
        return headers.getAsList(name);
    }

    public void addHeader(String name, String value) {
        headers.add(name, value);
    }

    public void setHeader(String name, String value) {
        headers.set(name, value);
    }

    public void removeHeader(String name) {
        headers.remove(name);
    }

    public Collection<String> getHeaderNames() {
        return headers.names();
    }
}
