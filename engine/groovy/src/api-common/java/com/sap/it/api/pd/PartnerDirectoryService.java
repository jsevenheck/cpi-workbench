package com.sap.it.api.pd;

import com.sap.it.api.ITApi;
import com.sap.it.api.pd.exception.PartnerDirectoryException;
import io.cpiwb.groovy.Platform;

/**
 * Partner Directory lookups against the project's mocked directory ({@code platform/partner-directory.json}).
 * Methods and their argument order follow SAP's published Javadoc of the generic API; behaviour for
 * missing entries (null) follows the documented {@code getAlternativePartnerId} contract and is assumed
 * for the other methods.
 */
public class PartnerDirectoryService implements ITApi {

    /** Reads a String or {@link BinaryData} parameter of a partner; null if it does not exist. */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String parameterId, String partnerId, Class<T> type) throws PartnerDirectoryException {
        require(parameterId, "parameterId");
        require(partnerId, "partnerId");
        if (type != null && type != String.class && type != BinaryData.class) {
            throw new PartnerDirectoryException("Unsupported parameter type " + type.getName()
                    + " (String or com.sap.it.api.pd.BinaryData)");
        }
        Object v = Platform.current().pdParameter(parameterId, partnerId, type == null ? null : type.getSimpleName());
        if (v == null) {
            return null;
        }
        if (type == null || type == String.class) {
            if (!(v instanceof String)) {
                throw new PartnerDirectoryException("Parameter " + parameterId + " of partner " + partnerId
                        + " is binary; read it with BinaryData.class");
            }
            return (T) v;
        }
        if (v instanceof String) {
            throw new PartnerDirectoryException("Parameter " + parameterId + " of partner " + partnerId
                    + " is a string parameter; read it with String.class");
        }
        return type.cast(v);
    }

    /** Partner ID for an alternative partner (agency, scheme, ID); null if none is maintained. */
    public String getPartnerId(String agency, String scheme, String alternativePartnerId) throws PartnerDirectoryException {
        require(agency, "agency");
        require(scheme, "scheme");
        require(alternativePartnerId, "alternativePartnerId");
        return Platform.current().pdPartnerId(agency, scheme, alternativePartnerId);
    }

    /** Alternative partner ID of a partner for agency and scheme; null if none is maintained. */
    public String getAlternativePartnerId(String agency, String scheme, String partnerId) throws PartnerDirectoryException {
        require(agency, "agency");
        require(scheme, "scheme");
        require(partnerId, "partnerId");
        return Platform.current().pdAlternativePartnerId(agency, scheme, partnerId);
    }

    /** Exists in SAP's API; its exact signature is not verified, so the simulator rejects the call. */
    public Object getCertificateParameter(Object... args) {
        Platform.current().recordCall("PartnerDirectoryService", "getCertificateParameter", args);
        throw new UnsupportedOperationException("UnsupportedInSimulator: PartnerDirectoryService.getCertificateParameter");
    }

    private static void require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
    }
}
