package com.sap.it.api.exception;

/** {@code ITApiFactory} has no handler for the requested API type. */
public class InvalidContextException extends ITApiException {

    public InvalidContextException() {
    }

    public InvalidContextException(String message) {
        super(message);
    }
}
