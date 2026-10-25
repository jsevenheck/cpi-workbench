package com.sap.it.api.nrc;

import com.sap.it.api.ITApi;
import com.sap.it.api.nrc.exception.NumberRangeConfigException;
import io.cpiwb.groovy.Platform;

/** Number Range Objects, mocked from the project's {@code platform/number-ranges.json}. */
public class NumberRangeConfigurationService implements ITApi {

    /** Next value of the number range; the same correlation ID (may be null) returns the value it got before. */
    public String getNextValuefromNumberRange(String name, String correlationId) throws NumberRangeConfigException {
        return Platform.current().nextNumber(name, correlationId);
    }
}
