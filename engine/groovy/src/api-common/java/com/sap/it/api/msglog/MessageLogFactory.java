package com.sap.it.api.msglog;

import io.cpiwb.groovy.Platform;

/** Bound as {@code messageLogFactory} in every script, as in Cloud Integration. */
public class MessageLogFactory {

    /** Returns null when the MPL is switched off (log level "None" in CPI). */
    public MessageLog getMessageLog(Object message) {
        Platform p = Platform.current();
        p.recordCall("MessageLogFactory", "getMessageLog");
        return p.isMplEnabled() ? new MessageLog(p) : null;
    }
}
