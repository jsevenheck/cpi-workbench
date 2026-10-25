package com.sap.it.api;

import com.sap.it.api.keystore.KeystoreService;
import com.sap.it.api.mapping.ValueMappingApi;
import com.sap.it.api.pd.PartnerDirectoryService;
import com.sap.it.api.securestore.SecureStoreService;
import io.cpiwb.groovy.Platform;

/**
 * Clean-room simulation of SAP's {@code ITApiFactory}. Supported services are backed by the mocked
 * platform data of the current project ({@code platform/} folder); others raise a clear error.
 */
public final class ITApiFactory {

    private ITApiFactory() {
    }

    /** Current API (SAP marks {@code getApi} deprecated in favour of this method). */
    public static <T> T getService(Class<T> type, Object context) {
        return create(type, "getService");
    }

    /** Deprecated in SAP's API, still widely used in scripts. */
    public static <T> T getApi(Class<T> type, Object context) {
        return create(type, "getApi");
    }

    private static <T> T create(Class<T> type, String how) {
        Platform.current().recordCall("ITApiFactory", how, type == null ? null : type.getName());
        if (type == SecureStoreService.class) {
            return type.cast(new SecureStoreService());
        }
        if (type == ValueMappingApi.class) {
            return type.cast(new ValueMappingApi());
        }
        if (type == PartnerDirectoryService.class) {
            return type.cast(new PartnerDirectoryService());
        }
        if (type == KeystoreService.class) {
            return type.cast(new KeystoreService());
        }
        throw new UnsupportedOperationException("UnsupportedInSimulator: " + (type == null ? "null" : type.getName())
                + " is not simulated yet (supported: SecureStoreService, ValueMappingApi, PartnerDirectoryService, KeystoreService)");
    }
}
