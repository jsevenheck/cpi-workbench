package com.sap.it.api.keystore;

import com.sap.it.api.keystore.exception.KeystoreException;
import io.cpiwb.groovy.Platform;

import java.security.Key;
import java.security.KeyPair;
import java.security.cert.Certificate;

/**
 * Tenant keystore access backed by a local keystore file ({@code platform/keystore.json} names a
 * PKCS#12 or JKS file). An unknown alias raises {@link KeystoreException}; whether CPI returns null
 * instead is not verified.
 */
public class KeystoreService {

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
}
