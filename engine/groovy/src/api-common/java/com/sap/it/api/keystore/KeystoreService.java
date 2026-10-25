package com.sap.it.api.keystore;

import com.sap.it.api.ITApi;
import com.sap.it.api.keystore.exception.KeystoreException;
import io.cpiwb.groovy.Platform;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.security.Key;
import java.security.KeyPair;
import java.security.cert.Certificate;

/**
 * Tenant keystore access backed by a local keystore file ({@code platform/keystore.json} names a
 * PKCS#12 or JKS file). Methods and null results follow SAP's published Javadoc
 * ({@code com.sap.cloud.adk:generic.api}): an unknown alias gives null. Trust managers use the
 * certificates of the same file.
 */
public class KeystoreService implements ITApi {

    public Key getKey(String alias) throws KeystoreException {
        return Platform.current().keystore("getKey", alias, Key.class);
    }

    public Certificate getCertificate(String alias) throws KeystoreException {
        return Platform.current().keystore("getCertificate", alias, Certificate.class);
    }

    public Certificate[] getCertificateChain(String alias) throws KeystoreException {
        return Platform.current().keystore("getCertificateChain", alias, Certificate[].class);
    }

    public KeyPair getKeyPair(String alias) throws KeystoreException {
        return Platform.current().keystore("getKeyPair", alias, KeyPair.class);
    }

    public KeyManager getKeyManager() throws KeystoreException {
        return first(Platform.current().keyManagers("getKeyManager", null));
    }

    public KeyManager getKeyManager(String alias) throws KeystoreException {
        return first(Platform.current().keyManagers("getKeyManager", alias));
    }

    public KeyManager[] getKeyManagers() throws KeystoreException {
        return Platform.current().keyManagers("getKeyManagers", null);
    }

    public KeyManager[] getKeyManagers(String alias) throws KeystoreException {
        return Platform.current().keyManagers("getKeyManagers", alias);
    }

    public TrustManager getTrustManager() throws KeystoreException {
        return first(Platform.current().trustManagers("getTrustManager"));
    }

    public TrustManager[] getTrustManagers() throws KeystoreException {
        return Platform.current().trustManagers("getTrustManagers");
    }

    private static <T> T first(T[] list) {
        return list == null || list.length == 0 ? null : list[0];
    }
}
