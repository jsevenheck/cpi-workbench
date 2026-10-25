package com.sap.it.script.v2.api.extensions;

import com.sap.it.script.v2.api.Payload;
import com.sap.it.script.v2.api.exceptions.ProcessDirectInvocationException;

import java.io.InputStream;

/** Calls another integration flow over ProcessDirect (Script API v2); not run by the simulator. */
public interface ProcessDirectService extends ExtensionFunctions {
    Payload invokeProcessDirect(String address, InputStream content) throws ProcessDirectInvocationException;
}
