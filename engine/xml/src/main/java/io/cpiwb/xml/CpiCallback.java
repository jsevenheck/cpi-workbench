package io.cpiwb.xml;

/**
 * Receives calls of the SAP extension functions {@code setHeader} / {@code setProperty}
 * (namespace {@code http://sap.com/it/}) made from a stylesheet.
 */
@FunctionalInterface
public interface CpiCallback {

    /** SAP's extension function namespace for XSLT mappings in Cloud Integration. */
    String NAMESPACE = "http://sap.com/it/";

    /**
     * @param function   local name of the called function ({@code setHeader} or {@code setProperty})
     * @param exchange   the object passed as first argument; must be the {@code $exchange} parameter
     * @param name       header/property name
     * @param value      header/property value as string
     */
    void call(String function, Object exchange, String name, String value);
}
