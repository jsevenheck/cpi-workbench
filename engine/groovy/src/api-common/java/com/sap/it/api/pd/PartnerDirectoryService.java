package com.sap.it.api.pd;

import com.sap.it.api.ITApi;
import com.sap.it.api.pd.exception.PartnerDirectoryException;
import io.cpiwb.groovy.Platform;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Partner Directory lookups against the project's mocked directory ({@code platform/partner-directory.json}).
 * Methods, null results and IllegalArgumentException cases follow SAP's published Javadoc
 * ({@code com.sap.cloud.adk:generic.api}). Authorized users come from {@code "authorizedUsers": [...]}.
 */
public class PartnerDirectoryService implements ITApi {

    /** Reads a String or {@link BinaryData} parameter of a partner; null if it does not exist. */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String parameterId, String partnerId, Class<T> type) throws PartnerDirectoryException {
        require(parameterId, "parameterId");
        require(partnerId, "partnerId");
        boolean binary = binary(type);
        Object v = Platform.current().pdParameter(parameterId, partnerId, type.getSimpleName());
        if (v == null) {
            return null;
        }
        if ((v instanceof BinaryData) != binary) {
            throw new PartnerDirectoryException("Parameter " + parameterId + " of partner " + partnerId + " is a "
                    + (binary ? "string" : "binary") + " parameter; read it with "
                    + (binary ? "String.class" : "BinaryData.class"));
        }
        return (T) v;
    }

    /** Deprecated in SAP's API. All parameters of the type; empty map if there are none. */
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getParameters(String partnerId, Class<T> type) throws PartnerDirectoryException {
        require(partnerId, "partnerId");
        return (Map<String, T>) Platform.current().pdParameters("getParameters", null, partnerId, binary(type));
    }

    /** Deprecated in SAP's API. Parameters whose IDs match a LIKE pattern ({@code _}, {@code %}, escape {@code \}). */
    @SuppressWarnings("unchecked")
    public <T> Map<String, T> getParametersMatchingPattern(String patternForParameterId, String partnerId, Class<T> type)
            throws PartnerDirectoryException {
        requireNotNull(partnerId, "partnerId");
        return (Map<String, T>) Platform.current().pdParameters("getParametersMatchingPattern", patternForParameterId,
                partnerId, binary(type));
    }

    /** Deprecated in SAP's API. IDs of the parameters whose IDs match a LIKE pattern. */
    public Collection<String> getParameterIdsMatchingPattern(String patternForParameterId, String partnerId, Class<?> type)
            throws PartnerDirectoryException {
        requireNotNull(partnerId, "partnerId");
        return new ArrayList<>(Platform.current().pdParameters("getParameterIdsMatchingPattern", patternForParameterId,
                partnerId, binary(type)).keySet());
    }

    /** X.509 certificate of a binary parameter with content type "crt"; null if it does not exist. */
    public Certificate getCertificateParameter(String parameterId, String partnerId) throws PartnerDirectoryException {
        require(parameterId, "parameterId");
        require(partnerId, "partnerId");
        Object v = Platform.current().pdParameter(parameterId, partnerId, "Certificate");
        if (v == null) {
            return null;
        }
        if (!(v instanceof BinaryData) || !"crt".equalsIgnoreCase(((BinaryData) v).getContentType())) {
            throw new PartnerDirectoryException("Parameter " + parameterId + " of partner " + partnerId
                    + " is not a certificate parameter (binary with content type \"crt\")");
        }
        try {
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(((BinaryData) v).getData()));
        } catch (CertificateException e) {
            throw new PartnerDirectoryException("Parameter " + parameterId + " of partner " + partnerId
                    + " is no valid X.509 certificate: " + e.getMessage(), e);
        }
    }

    /** Partner ID to which an authorized user is assigned; null if none. */
    public String getPartnerIdOfAuthorizedUser(String authorizedUser) throws PartnerDirectoryException {
        require(authorizedUser, "authorizedUser");
        return Platform.current().pdPartnerIdOfAuthorizedUser(authorizedUser);
    }

    /** Authorized users of a partner; empty list if none. */
    public List<String> getAuthorizedUsers(String partnerId) throws PartnerDirectoryException {
        require(partnerId, "partnerId");
        return Platform.current().pdAuthorizedUsers(partnerId);
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

    /** String.class → false, BinaryData.class → true; any other type is rejected as SAP documents. */
    private static boolean binary(Class<?> type) {
        if (type == String.class) {
            return false;
        }
        if (type == BinaryData.class) {
            return true;
        }
        throw new IllegalArgumentException("Unsupported parameter type " + (type == null ? "null" : type.getName())
                + " (String or com.sap.it.api.pd.BinaryData)");
    }

    private static void require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
    }

    private static void requireNotNull(String v, String name) {
        if (v == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
