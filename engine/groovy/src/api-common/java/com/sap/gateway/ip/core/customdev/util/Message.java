package com.sap.gateway.ip.core.customdev.util;

import io.cpiwb.groovy.MessageSupport;
import org.apache.camel.Exchange;

/**
 * Script API v1 message ({@code com.sap.gateway.ip.core.customdev.util.Message}), clean-room
 * implementation of the publicly documented methods: body, headers, exchange properties, attachments.
 */
public class Message extends MessageSupport {

    public Message(Exchange exchange) {
        super(exchange);
    }
}
