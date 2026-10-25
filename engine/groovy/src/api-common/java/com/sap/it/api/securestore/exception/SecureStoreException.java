package com.sap.it.api.securestore.exception;

import com.sap.it.api.exception.ITApiException;

/** Secure Store access failed. */
public class SecureStoreException extends ITApiException {

    public SecureStoreException() {
    }

    public SecureStoreException(String message) {
        super(message);
    }

    public SecureStoreException(String message, Exception cause) {
        super(message, cause);
    }

    public SecureStoreException(Exception cause) {
        super(cause);
    }
}
