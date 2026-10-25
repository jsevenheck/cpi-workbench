package io.cpiwb.groovy;

import com.sap.it.api.keystore.exception.KeystoreException;
import com.sap.it.api.pd.BinaryData;
import io.cpiwb.core.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of the current script run that the simulated SAP APIs read and write: mocked platform data
 * (secure store, value mappings, partner directory, keystore), the message processing log and a log of API calls. The engine runs
 * one script at a time, so a single current instance is enough.
 */
public final class Platform {

    private static volatile Platform current = new Platform(Map.of(), List.of(), true);

    /** partner ID → {parameters, alternativePartners} */
    private Map<String, Object> partners = Map.of();
    /** {file, password, type, keyPasswords}; file already resolved against the project. */
    private Map<String, Object> keystoreConfig;
    private Path baseDir;
    private KeyStore keystore;

    /** alias → {username, password, properties} */
    private final Map<String, Map<String, Object>> credentials;
    private final List<Map<String, Object>> valueMappings;
    private final boolean mplEnabled;
    private Map<String, Object> numberRanges = Map.of();
    private final Map<String, Long> numberNext = new LinkedHashMap<>();
    private final Map<String, String> numberByCorrelation = new LinkedHashMap<>();

    private final List<Object> apiCalls = new ArrayList<>();
    private final List<Object> mplProperties = new ArrayList<>();
    private final List<Object> mplCustomHeaders = new ArrayList<>();
    private final List<Object> mplAttachments = new ArrayList<>();

    public Platform(Map<String, Map<String, Object>> credentials, List<Map<String, Object>> valueMappings,
                    boolean mplEnabled) {
        this.credentials = credentials;
        this.valueMappings = valueMappings;
        this.mplEnabled = mplEnabled;
    }

    public static Platform current() {
        return current;
    }

    static void setCurrent(Platform p) {
        current = p;
    }

    public boolean isMplEnabled() {
        return mplEnabled;
    }

    public void recordCall(String api, String method, Object... args) {
        List<Object> a = new ArrayList<>();
        for (Object o : args) {
            a.add(o == null ? null : o.toString());
        }
        apiCalls.add(Json.o().with("api", api).with("method", method).with("args", a));
    }

    // ---------------------------------------------------------------- secure store

    public Map<String, Object> credential(String alias) {
        return alias == null ? null : credentials.get(alias);
    }

    // ---------------------------------------------------------------- value mapping

    public String mappedValue(String sa, String si, String sv, String ta, String ti) {
        recordCall("ValueMappingApi", "getMappedValue", sa, si, sv, ta, ti);
        for (Map<String, Object> m : valueMappings) {
            if (eq(m, "sourceAgency", sa) && eq(m, "sourceIdentifier", si) && eq(m, "sourceValue", sv)
                    && eq(m, "targetAgency", ta) && eq(m, "targetIdentifier", ti)) {
                return Json.str(m, "targetValue", null);
            }
        }
        return null;
    }

    private static boolean eq(Map<String, Object> m, String key, String value) {
        return value != null && value.equals(Json.str(m, key, null));
    }

    // ---------------------------------------------------------------- partner directory

    /** String value, {@link BinaryData}, or null. */
    @SuppressWarnings("unchecked")
    public Object pdParameter(String parameterId, String partnerId, String type) {
        recordCall("PartnerDirectoryService", "getParameter", parameterId, partnerId, type);
        Map<String, Object> params = pdRawParameters(partnerId);
        return pdValue(parameterId, partnerId, params == null ? null : params.get(parameterId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pdRawParameters(String partnerId) {
        Object partner = partners.get(partnerId);
        return partner instanceof Map ? Json.obj((Map<String, Object>) partner, "parameters") : null;
    }

    /**
     * Parameters of a partner whose IDs match a pattern with SQL LIKE wildcards ({@code _}, {@code %},
     * escape {@code \}) as documented for {@code getParametersMatchingPattern}; null pattern = all.
     * {@code binary} selects BinaryData (true) or String (false) parameters.
     */
    public Map<String, Object> pdParameters(String method, String pattern, String partnerId, boolean binary) {
        recordCall("PartnerDirectoryService", method, pattern, partnerId, binary ? "BinaryData" : "String");
        java.util.regex.Pattern re = pattern == null ? null : likePattern(pattern);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> params = pdRawParameters(partnerId);
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (re != null && !re.matcher(e.getKey()).matches()) {
                    continue;
                }
                Object v = pdValue(e.getKey(), partnerId, e.getValue());
                if (v != null && (v instanceof BinaryData) == binary) {
                    out.put(e.getKey(), v);
                }
            }
        }
        return out;
    }

    private static java.util.regex.Pattern likePattern(String pattern) {
        if (pattern.indexOf('[') >= 0 || pattern.indexOf(']') >= 0) {
            throw new IllegalArgumentException("The pattern must not contain '[' or ']': " + pattern);
        }
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length()) {
                re.append(java.util.regex.Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (ch == '_') {
                re.append('.');
            } else if (ch == '%') {
                re.append(".*");
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
        }
        return java.util.regex.Pattern.compile(re.toString(), java.util.regex.Pattern.DOTALL);
    }

    /** Authorized users of a partner ({@code "authorizedUsers": [...]} in the mocked directory). */
    @SuppressWarnings("unchecked")
    public List<String> pdAuthorizedUsers(String partnerId) {
        recordCall("PartnerDirectoryService", "getAuthorizedUsers", partnerId);
        List<String> out = new ArrayList<>();
        Object partner = partners.get(partnerId);
        if (partner instanceof Map) {
            for (Object u : Json.arr((Map<String, Object>) partner, "authorizedUsers")) {
                out.add(String.valueOf(u));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public String pdPartnerIdOfAuthorizedUser(String user) {
        recordCall("PartnerDirectoryService", "getPartnerIdOfAuthorizedUser", user);
        for (Map.Entry<String, Object> e : partners.entrySet()) {
            if (e.getValue() instanceof Map && Json.arr((Map<String, Object>) e.getValue(), "authorizedUsers").contains(user)) {
                return e.getKey();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object pdValue(String parameterId, String partnerId, Object v) {
        if (v == null || v instanceof String) {
            return v;
        }
        if (v instanceof Map) {
            Map<String, Object> b = (Map<String, Object>) v;
            byte[] data;
            String file = Json.str(b, "file", null);
            try {
                data = file != null ? Files.readAllBytes(platformFile(file))
                        : Base64.getMimeDecoder().decode(Json.str(b, "base64", ""));
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot read binary parameter " + parameterId + " of partner "
                        + partnerId + ": " + e.getMessage(), e);
            }
            return new SimBinaryData(data, Json.str(b, "contentType", "application/octet-stream"));
        }
        return v.toString();
    }

    public String pdPartnerId(String agency, String scheme, String alternativeId) {
        recordCall("PartnerDirectoryService", "getPartnerId", agency, scheme, alternativeId);
        for (Map.Entry<String, Object> e : partners.entrySet()) {
            for (Map<String, Object> alt : alternatives(e.getValue())) {
                if (agency.equals(Json.str(alt, "agency", null)) && scheme.equals(Json.str(alt, "scheme", null))
                        && alternativeId.equals(Json.str(alt, "id", null))) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    public String pdAlternativePartnerId(String agency, String scheme, String partnerId) {
        recordCall("PartnerDirectoryService", "getAlternativePartnerId", agency, scheme, partnerId);
        for (Map<String, Object> alt : alternatives(partners.get(partnerId))) {
            if (agency.equals(Json.str(alt, "agency", null)) && scheme.equals(Json.str(alt, "scheme", null))) {
                return Json.str(alt, "id", null);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alternatives(Object partner) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (partner instanceof Map) {
            for (Object o : Json.arr((Map<String, Object>) partner, "alternativePartners")) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    private record SimBinaryData(byte[] data, String contentType) implements BinaryData {
        @Override
        public byte[] getData() {
            return data.clone();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String toString() {
            return "BinaryData[" + contentType + ", " + data.length + " bytes]";
        }
    }

    // ---------------------------------------------------------------- keystore

    /**
     * One KeystoreService call: getKey, getCertificate, getCertificateChain or getKeyPair. As in SAP's
     * Javadoc, an unknown alias or an entry of the wrong kind gives null; a key that cannot be
     * recovered (wrong key password) gives IllegalStateException.
     */
    public <T> T keystore(String method, String alias, Class<T> type) throws KeystoreException {
        recordCall("KeystoreService", method, alias);
        KeyStore ks = loadKeystore();
        try {
            if (alias == null || !ks.containsAlias(alias)) {
                return null;
            }
            Object r;
            switch (method) {
                case "getCertificate":
                    r = ks.getCertificate(alias);
                    break;
                case "getCertificateChain":
                    r = ks.getCertificateChain(alias);
                    break;
                case "getKey":
                    r = key(ks, alias);
                    break;
                case "getKeyPair": {
                    Key k = key(ks, alias);
                    Certificate c = ks.getCertificate(alias);
                    r = k instanceof PrivateKey && c != null ? new KeyPair(c.getPublicKey(), (PrivateKey) k) : null;
                    break;
                }
                default:
                    throw new IllegalArgumentException(method);
            }
            return type.cast(r);
        } catch (java.security.KeyStoreException e) {
            throw new KeystoreException("Keystore access for alias " + alias + " failed: " + e.getMessage(), e);
        }
    }

    /** Key managers over the simulator keystore, or over just the given alias. */
    public javax.net.ssl.KeyManager[] keyManagers(String method, String alias) throws KeystoreException {
        recordCall("KeystoreService", method, alias);
        KeyStore ks = loadKeystore();
        try {
            KeyStore source = ks;
            char[] pw = keyPassword(alias == null ? "" : alias).toCharArray();
            if (alias != null) {
                if (!ks.isKeyEntry(alias)) {
                    return null;
                }
                source = KeyStore.getInstance("PKCS12");
                source.load(null, null);
                source.setKeyEntry(alias, ks.getKey(alias, pw), pw, ks.getCertificateChain(alias));
            }
            javax.net.ssl.KeyManagerFactory f = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            f.init(source, pw);
            return f.getKeyManagers();
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Key managers for the simulator keystore failed: " + e.getMessage(), e);
        }
    }

    /** Trust managers over the certificates of the simulator keystore. */
    public javax.net.ssl.TrustManager[] trustManagers(String method) throws KeystoreException {
        recordCall("KeystoreService", method);
        KeyStore ks = loadKeystore();
        try {
            javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            f.init(ks);
            return f.getTrustManagers();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Trust managers for the simulator keystore failed: " + e.getMessage(), e);
        }
    }

    private String keyPassword(String alias) {
        Map<String, Object> kp = Json.obj(keystoreConfig, "keyPasswords");
        return kp != null && kp.get(alias) != null ? kp.get(alias).toString() : Json.str(keystoreConfig, "password", "");
    }

    private Key key(KeyStore ks, String alias) throws java.security.KeyStoreException {
        if (!ks.isKeyEntry(alias)) {
            return null;
        }
        try {
            return ks.getKey(alias, keyPassword(alias).toCharArray());
        } catch (java.security.UnrecoverableKeyException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("The key of alias " + alias + " cannot be recovered (check keyPasswords "
                    + "in platform/keystore.json): " + e.getMessage(), e);
        }
    }

    private KeyStore loadKeystore() throws KeystoreException {
        if (keystore != null) {
            return keystore;
        }
        if (keystoreConfig == null || Json.str(keystoreConfig, "file", null) == null) {
            throw new KeystoreException("No keystore configured for the simulator: create platform/keystore.json "
                    + "with {\"file\": \"keystore.p12\", \"password\": \"...\"}");
        }
        String file = Json.str(keystoreConfig, "file", null);
        try {
            Path p = platformFile(file);
            char[] pw = Json.str(keystoreConfig, "password", "").toCharArray();
            String type = Json.str(keystoreConfig, "type", null);
            KeyStore ks;
            if (type == null) {
                ks = KeyStore.getInstance(p.toFile(), pw);
            } else {
                ks = KeyStore.getInstance(type);
                try (InputStream in = Files.newInputStream(p)) {
                    ks.load(in, pw);
                }
            }
            keystore = ks;
            return ks;
        } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new KeystoreException("Cannot open the simulator keystore " + file + ": " + e.getMessage(), e);
        }
    }

    /** Resolves a file named in platform data, relative to the project's platform/ folder. */
    private Path platformFile(String name) throws IOException {
        Path base = baseDir == null ? Path.of("").toAbsolutePath() : baseDir.resolve("platform");
        Path p = base.resolve(name).normalize();
        if (baseDir != null && !p.startsWith(baseDir)) {
            throw new IOException("File outside the project: " + name);
        }
        return p;
    }

    // ---------------------------------------------------------------- number ranges

    /**
     * Next value of a mocked number range ({@code platform/number-ranges.json}:
     * {@code {"NAME": {"next": 1000, "min": 1, "max": 9999, "fieldLength": 6, "rotate": true}}}).
     * Values count up within one run only; nothing is written back. The same correlation ID returns the
     * same value again, as SAP documents.
     */
    public String nextNumber(String name, String correlationId) throws com.sap.it.api.nrc.exception.NumberRangeConfigException {
        recordCall("NumberRangeConfigurationService", "getNextValuefromNumberRange", name, correlationId);
        if (correlationId != null && numberByCorrelation.containsKey(name + "\0" + correlationId)) {
            return numberByCorrelation.get(name + "\0" + correlationId);
        }
        Object def = name == null ? null : numberRanges.get(name);
        if (!(def instanceof Map)) {
            throw new com.sap.it.api.nrc.exception.NumberRangeConfigException("Number range " + name
                    + " is not defined in the simulator's platform/number-ranges.json");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) def;
        long min = Json.num(d, "min", 0);
        long max = Json.num(d, "max", Long.MAX_VALUE);
        long next = numberNext.containsKey(name) ? numberNext.get(name) : Json.num(d, "next", min);
        if (next > max) {
            if (!Json.bool(d, "rotate", false)) {
                throw new com.sap.it.api.nrc.exception.NumberRangeConfigException("Number range " + name
                        + " is exhausted (max " + max + ")");
            }
            next = min;
        }
        numberNext.put(name, next + 1);
        long width = Json.num(d, "fieldLength", 0);
        String value = width > 0 ? String.format("%0" + width + "d", next) : Long.toString(next);
        if (correlationId != null) {
            numberByCorrelation.put(name + "\0" + correlationId, value);
        }
        return value;
    }

    // ---------------------------------------------------------------- message processing log

    public void mplProperty(String type, String name, Object value) {
        mplProperties.add(Json.o().with("name", name).with("type", type)
                .with("value", value instanceof Date ? ((Date) value).toInstant().toString() : value == null ? null : value.toString()));
    }

    public void mplCustomHeader(String name, String value) {
        mplCustomHeaders.add(Json.o().with("name", name).with("value", value));
    }

    public void mplAttachment(String name, String content, String mediaType) {
        mplAttachments.add(Json.o().with("name", name).with("mediaType", mediaType).with("content", content));
    }

    public Map<String, Object> mplJson() {
        return Json.o().with("enabled", mplEnabled).with("properties", mplProperties)
                .with("customHeaders", mplCustomHeaders).with("attachments", mplAttachments);
    }

    public List<Object> apiCallsJson() {
        return apiCalls;
    }

    /** Reads the {@code platform} object of a request (credentials, valueMappings, partnerDirectory, keystore, mpl). */
    @SuppressWarnings("unchecked")
    static Platform fromJson(Map<String, Object> p, Path baseDir) {
        Map<String, Map<String, Object>> creds = new LinkedHashMap<>();
        Object c = p == null ? null : p.get("credentials");
        if (c instanceof Map) {
            ((Map<String, Object>) c).forEach((k, v) -> {
                if (v instanceof Map) {
                    creds.put(k, (Map<String, Object>) v);
                }
            });
        } else if (c instanceof List) {
            for (Object o : (List<Object>) c) {
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    creds.put(Json.str(m, "alias", Json.str(m, "name", "")), m);
                }
            }
        }
        List<Map<String, Object>> vms = new ArrayList<>();
        for (Object o : Json.arr(p, "valueMappings")) {
            if (o instanceof Map) {
                vms.add((Map<String, Object>) o);
            }
        }
        Platform r = new Platform(creds, vms, Json.bool(p, "mplEnabled", true));
        r.baseDir = baseDir;
        Map<String, Object> pd = Json.obj(p, "partnerDirectory");
        Map<String, Object> partners = pd == null ? null : Json.obj(pd, "partners");
        r.partners = partners == null ? Map.of() : partners;
        r.keystoreConfig = Json.obj(p, "keystore");
        Map<String, Object> nr = Json.obj(p, "numberRanges");
        r.numberRanges = nr == null ? Map.of() : nr;
        return r;
    }
}
