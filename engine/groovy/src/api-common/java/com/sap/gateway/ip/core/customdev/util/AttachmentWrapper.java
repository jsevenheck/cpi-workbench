package com.sap.gateway.ip.core.customdev.util;

import javax.activation.DataHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A message attachment with its MIME headers (simulated; constructor shape not verified against SAP). */
public class AttachmentWrapper {

    private final DataHandler dataHandler;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public AttachmentWrapper(DataHandler dataHandler) {
        this.dataHandler = dataHandler;
    }

    public DataHandler getDataHandler() {
        return dataHandler;
    }

    public void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public long size() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            dataHandler.writeTo(out);
            return out.size();
        } catch (IOException e) {
            return 0;
        }
    }
}
