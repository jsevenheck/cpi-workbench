package com.sap.it.script.v2.api.exceptions;

/** Base exception of a failed integration scenario step (Script API v2). */
public class ScenarioExecutionException extends Exception {

    private String exceptionType;

    public ScenarioExecutionException() {
    }

    public ScenarioExecutionException(String message) {
        super(message);
    }

    public ScenarioExecutionException(Throwable cause) {
        super(cause);
    }

    public ScenarioExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(String exceptionType) {
        this.exceptionType = exceptionType;
    }
}
