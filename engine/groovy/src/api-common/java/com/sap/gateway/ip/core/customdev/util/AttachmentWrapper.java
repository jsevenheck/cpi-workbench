package com.sap.gateway.ip.core.customdev.util;

import io.cpiwb.groovy.AttachmentHeaders;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import java.util.Collection;
import java.util.List;

/**
 * A message attachment with its MIME headers. Constructors and methods follow SAP's published Script
 * API ({@code com.sap.cloud.script:script.api}). SAP's class extends Camel's
 * {@code DefaultAttachment}; this one does not, so it is no {@code org.apache.camel.Attachment}.
 */
public class AttachmentWrapper {

    private final DataHandler dataHandler;
    private final AttachmentHeaders headers = new AttachmentHeaders();

    public AttachmentWrapper(DataHandler dataHandler) {
        this.dataHandler = dataHandler;
    }

    public AttachmentWrapper(DataSource dataSource) {
        this(new DataHandler(dataSource));
    }

    public DataHandler getDataHandler() {
        return dataHandler;
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
