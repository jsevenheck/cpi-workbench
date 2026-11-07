package com.sap.it.api.mapping;

import com.sap.it.api.msg.ExchangePropertyProvider;

/**
 * The context a Message Mapping passes to the custom functions of its scripts: headers and exchange
 * properties of the message. Signatures and null result follow SAP's published Javadoc of the Cloud
 * Integration API ({@code com.sap.it.api.mapping}).
 */
public interface MappingContext extends ExchangePropertyProvider {

    /** The value of the header, or null if there is none. */
    Object getHeader(String name);

    /** Creates the header or overwrites its value. */
    void setHeader(String name, Object value);
}
