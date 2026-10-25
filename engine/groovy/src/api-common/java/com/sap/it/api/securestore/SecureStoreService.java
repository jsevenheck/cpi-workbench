package com.sap.it.api.securestore;

import com.sap.it.api.ITApi;
import com.sap.it.api.securestore.exception.SecureStoreException;
import io.cpiwb.core.Json;
import io.cpiwb.groovy.Platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads user credentials from the project's mocked secure store ({@code platform/credentials.json}). */
public class SecureStoreService implements ITApi {

    public UserCredential getUserCredential(String alias) throws SecureStoreException {
        Map<String, Object> c = Platform.current().credential(alias);
        if (c == null) {
            throw new SecureStoreException("Could not fetch the credential for alias " + alias
                    + " (not defined in the simulator's platform/credentials.json)");
        }
        Map<String, String> props = new LinkedHashMap<>();
        Map<String, Object> raw = Json.obj(c, "properties");
        if (raw != null) {
            raw.forEach((k, v) -> props.put(k, v == null ? null : v.toString()));
        }
        return new UserCredential(alias, Json.str(c, "username", null),
                Json.str(c, "password", "").toCharArray(), props);
    }
}
