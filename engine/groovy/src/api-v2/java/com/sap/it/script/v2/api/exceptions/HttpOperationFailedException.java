package com.sap.it.script.v2.api.exceptions;

import java.util.Map;

/** An HTTP call answered with an error status (Script API v2). */
public class HttpOperationFailedException extends ScenarioExecutionException {

    private final String url;
    private final int statusCode;
    private final String statusText;
    private final String redirectLocation;
    private final Map<String, String> responseHeaders;
    private final String responseBody;

    public HttpOperationFailedException(String url, int statusCode, String statusText, String location,
                                        Map<String, String> responseHeaders, String responseBody) {
        super("HTTP operation failed invoking " + printableUri(url) + " with statusCode: " + statusCode
                + (location != null ? ", redirectLocation: " + location : ""));
        this.url = url;
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.redirectLocation = location;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
    }

    public String getUrl() { return url; }
    public boolean isRedirectError() { return statusCode >= 300 && statusCode < 400; }
    public boolean hasRedirectLocation() { return redirectLocation != null; }
    public String getRedirectLocation() { return redirectLocation; }
    public int getStatusCode() { return statusCode; }
    public String getStatusText() { return statusText; }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public String getResponseBody() { return responseBody; }

    /** The URI with the password of its user info masked (simulator's rule; SAP's is not published). */
    public static String printableUri(String uri) {
        return uri == null ? null : uri.replaceAll("(://[^/:@]*:)[^/@]*@", "$1xxxxxx@");
    }
}
