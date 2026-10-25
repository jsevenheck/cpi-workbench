package com.sap.it.api.securestore.exception;

import com.sap.it.api.exception.ITApiException;

/** A credential does not exist (SAP's API has the class; which calls throw it is not documented). */
public class CredentialNotFoundException extends ITApiException {

    public CredentialNotFoundException() {
    }

    public CredentialNotFoundException(String message) {
        super(message);
    }

    public CredentialNotFoundException(String message, Exception cause) {
        super(message, cause);
    }

    public CredentialNotFoundException(Exception cause) {
        super(cause);
    }
}
