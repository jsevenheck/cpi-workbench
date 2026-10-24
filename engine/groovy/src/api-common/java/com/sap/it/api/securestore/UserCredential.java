package com.sap.it.api.securestore;

import java.util.Map;

/** A deployed user credential (simulated). */
public class UserCredential {

    private final String alias;
    private final String username;
    private final char[] password;
    private final Map<String, String> properties;

    public UserCredential(String alias, String username, char[] password, Map<String, String> properties) {
        this.alias = alias;
        this.username = username;
        this.password = password;
        this.properties = properties;
    }

    public String getAlias() { return alias; }
    public String getUsername() { return username; }
    public char[] getPassword() { return password.clone(); }
    public Map<String, String> getCredentialProperties() { return properties; }
}
