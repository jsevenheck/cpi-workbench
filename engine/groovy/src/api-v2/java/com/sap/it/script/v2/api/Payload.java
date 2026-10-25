package com.sap.it.script.v2.api;

import java.io.InputStream;

/** The response of a ProcessDirect call (Script API v2). */
public interface Payload {
    InputStream getContent();
}
