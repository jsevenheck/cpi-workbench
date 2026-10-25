package com.sap.it.api.exception;

/** Base exception of SAP's platform APIs (published in com.sap.cloud.adk:generic.api). */
public class ITApiRuntimeException extends RuntimeException {

    public ITApiRuntimeException() {
    }

    public ITApiRuntimeException(String message, Exception cause) {
        super(message, cause);
    }

    public ITApiRuntimeException(Exception cause) {
        super(cause);
    }

    public ITApiRuntimeException(String message) {
        super(message);
    }
}
