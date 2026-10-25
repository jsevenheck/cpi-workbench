package com.sap.it.api.keystore.exception;

public class KeystoreException extends Exception {
    public KeystoreException(String message) {
        super(message);
    }

    public KeystoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
