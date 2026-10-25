package com.sap.it.api.keystore.exception;

import com.sap.it.api.exception.ITApiException;

/** Keystore access failed. */
public class KeystoreException extends ITApiException {

    public KeystoreException() {
    }

    public KeystoreException(String message) {
        super(message);
    }

    public KeystoreException(String message, Exception cause) {
        super(message, cause);
    }

    public KeystoreException(Exception cause) {
        super(cause);
    }
}
