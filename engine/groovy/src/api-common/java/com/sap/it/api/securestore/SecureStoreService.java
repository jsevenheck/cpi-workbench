package com.sap.it.api.securestore;

import com.sap.it.api.ITApi;
import com.sap.it.api.securestore.exception.SecureStoreException;
import io.cpiwb.core.Json;
import io.cpiwb.groovy.Platform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads deployed credentials from the project's mocked secure store ({@code platform/credentials.json}).
 * OAuth2 credentials carry a fixed {@code accessToken}; no token server is called.
 */
public class SecureStoreService implements ITApi {

    public UserCredential getUserCredential(String alias) throws SecureStoreException {
        Map<String, Object> c = lookup("getUserCredential", alias);
        return new UserCredential(alias, Json.str(c, "username", null),
                Json.str(c, "password", "").toCharArray(), strings(Json.obj(c, "properties")));
    }

    public AccessTokenAndUser getAccesTokenForOauth2ClientCredential(String credentialName) throws SecureStoreException {
        return token("getAccesTokenForOauth2ClientCredential", credentialName);
    }

    public AccessTokenAndUser getAccesTokenForOauth2AuthorizationCodeCredential(String credentialName)
            throws SecureStoreException {
        return token("getAccesTokenForOauth2AuthorizationCodeCredential", credentialName);
    }

    private static AccessTokenAndUser token(String method, String name) throws SecureStoreException {
        Map<String, Object> c = lookup(method, name);
        String token = Json.str(c, "accessToken", null);
        if (token == null) {
            throw new SecureStoreException("Credential " + name + " is no OAuth2 credential in the simulator's "
                    + "platform/credentials.json (it needs \"accessToken\")");
        }
        Map<String, String> response = strings(Json.obj(c, "tokenResponse"));
        response.putIfAbsent("access_token", token);
        return new AccessTokenAndUser(token, Json.str(c, "user", ""), response);
    }

    private static Map<String, Object> lookup(String method, String alias) throws SecureStoreException {
        Platform.current().recordCall("SecureStoreService", method, alias);
        if (alias == null || alias.isEmpty()) {
            throw new SecureStoreException("The alias must not be null or empty");
        }
        Map<String, Object> c = Platform.current().credential(alias);
        if (c == null) {
            throw new SecureStoreException("Could not fetch the credential for alias " + alias
                    + " (not defined in the simulator's platform/credentials.json)");
        }
        return c;
    }

    private static Map<String, String> strings(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> out.put(k, v == null ? null : v.toString()));
        }
        return out;
    }
}
