package com.sap.it.api.exception;

/** Base exception of SAP's platform APIs (published in com.sap.cloud.adk:generic.api). */
public class ITApiException extends Exception {

    public ITApiException() {
    }

    public ITApiException(String message, Exception cause) {
        super(message, cause);
    }

    public ITApiException(Exception cause) {
        super(cause);
    }

    public ITApiException(String message) {
        super(message);
    }
}
