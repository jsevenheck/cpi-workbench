package com.sap.it.script.v2.api;

import io.cpiwb.groovy.MessageSupport;
import org.apache.camel.Exchange;

/**
 * Script API v2 message ({@code com.sap.it.script.v2.api.Message}). PROVISIONAL: SAP's v2 method
 * list is not verified yet (PLAN.md F6, probe fingerprint-v2.groovy); this offers the same methods
 * as the v1 message.
 */
public class Message extends MessageSupport {

    public Message(Exchange exchange) {
        super(exchange);
    }
}
