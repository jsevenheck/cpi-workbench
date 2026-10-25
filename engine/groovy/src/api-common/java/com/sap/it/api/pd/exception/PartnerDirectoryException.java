package com.sap.it.api.pd.exception;

import com.sap.it.api.exception.ITApiException;

/** Partner Directory access failed. */
public class PartnerDirectoryException extends ITApiException {

    public PartnerDirectoryException() {
    }

    public PartnerDirectoryException(String message) {
        super(message);
    }

    public PartnerDirectoryException(String message, Exception cause) {
        super(message, cause);
    }

    public PartnerDirectoryException(Exception cause) {
        super(cause);
    }
}
