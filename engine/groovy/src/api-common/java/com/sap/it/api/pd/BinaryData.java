package com.sap.it.api.pd;

/** A binary Partner Directory parameter: content and its media type. */
public interface BinaryData {

    byte[] getData();

    String getContentType();
}
