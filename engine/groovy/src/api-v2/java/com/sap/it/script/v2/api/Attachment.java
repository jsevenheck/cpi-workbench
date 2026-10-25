package com.sap.it.script.v2.api;

import javax.activation.DataHandler;
import java.util.Collection;
import java.util.List;

/** A message attachment with its MIME headers (Script API v2). */
public interface Attachment {
    DataHandler getDataHandler();
    String getHeader(String name);
    List<String> getHeaderAsList(String name);
    Collection<String> getHeaderNames();
    void setHeader(String name, String value);
    void addHeader(String name, String value);
    void removeHeader(String name);
    void clearHeaders();
}
