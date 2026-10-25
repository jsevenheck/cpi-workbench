package com.sap.it.api.securestore;

import java.util.Map;

/** Access token of an OAuth2 credential (SAP's interface; here a simple value class). */
public class AccessTokenAndUser {

    private final String accessToken;
    private final String user;
    private final Map<String, String> tokenResponse;

    public AccessTokenAndUser(String accessToken, String user, Map<String, String> tokenResponse) {
        this.accessToken = accessToken;
        this.user = user;
        this.tokenResponse = tokenResponse;
    }

    public String getAccessToken() { return accessToken; }
    public String getUser() { return user; }
    public Map<String, String> getTokenResponse() { return tokenResponse; }
}
