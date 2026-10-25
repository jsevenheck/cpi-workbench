package com.sap.it.script.v2.api.exceptions;

/** A ProcessDirect call failed (Script API v2). */
public class ProcessDirectInvocationException extends Exception {

    public ProcessDirectInvocationException(String message) {
        super(message);
    }

    public ProcessDirectInvocationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProcessDirectInvocationException(Throwable cause) {
        super(cause);
    }
}
