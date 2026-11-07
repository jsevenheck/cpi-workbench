package com.sap.it.api.msg;

/**
 * Access to the exchange properties of a message. Signatures and null result follow SAP's published
 * Javadoc of the Cloud Integration API ({@code com.sap.it.api.msg}).
 */
public interface ExchangePropertyProvider {

    /** The value of the property, or null if there is none. */
    Object getProperty(String name);

    /** Creates the property or overwrites its value. */
    void setProperty(String name, Object value);
}
