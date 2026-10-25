package com.sap.it.script.v2.api.extensions;

/**
 * Exchange timestamps (Script API v2). The simulator returns the time the message was created for
 * both; how CPI's two values differ is not known.
 */
public interface ExchangeExtensionFunctions extends ExtensionFunctions {
    long getCreated();
    long getExchangeCreated();
}
