package com.sap.it.api.mapping;

import com.sap.it.api.ITApi;
import io.cpiwb.groovy.Platform;

/** Value mapping lookups against the project's mocked value mappings ({@code platform/value-mappings.json}). */
public class ValueMappingApi implements ITApi {

    /** Returns the target value, or null if no mapping exists (as in CPI). */
    public String getMappedValue(String sourceAgency, String sourceIdentifier, String sourceValue,
                                 String targetAgency, String targetIdentifier) {
        return Platform.current().mappedValue(sourceAgency, sourceIdentifier, sourceValue, targetAgency, targetIdentifier);
    }
}
