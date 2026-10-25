package com.sap.it.script.v2.api.attachment;

import com.sap.it.script.v2.api.Attachment;
import io.cpiwb.groovy.AttachmentHeaders;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import java.util.Collection;
import java.util.List;

/** Default {@link Attachment} (Script API v2); header rules as Camel's DefaultAttachment. */
public class DefaultAttachment implements Attachment {

    private final DataHandler dataHandler;
    private final AttachmentHeaders headers = new AttachmentHeaders();

    public DefaultAttachment(DataHandler dataHandler) {
        this.dataHandler = dataHandler;
    }

    public DefaultAttachment(DataSource dataSource) {
        this(new DataHandler(dataSource));
    }

    public DataHandler getDataHandler() { return dataHandler; }
    public String getHeader(String name) { return headers.get(name); }
    public List<String> getHeaderAsList(String name) { return headers.getAsList(name); }
    public void addHeader(String name, String value) { headers.add(name, value); }
    public void setHeader(String name, String value) { headers.set(name, value); }
    public void removeHeader(String name) { headers.remove(name); }
    public Collection<String> getHeaderNames() { return headers.names(); }
    public void clearHeaders() { headers.clear(); }
}
