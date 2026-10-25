package com.sap.it.api.nrc.exception;

/** The next value of a number range cannot be retrieved. */
public class NumberRangeConfigException extends Exception {

    public NumberRangeConfigException(String message) {
        super(message);
    }
}
