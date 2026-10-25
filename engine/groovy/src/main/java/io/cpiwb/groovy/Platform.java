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
        recordCall("SecureStoreService", "getUserCredential", alias);
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
        Object partner = partners.get(partnerId);
        Map<String, Object> params = partner instanceof Map ? Json.obj((Map<String, Object>) partner, "parameters") : null;
        Object v = params == null ? null : params.get(parameterId);
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

    /** One KeystoreService call: getKey, getCertificate, getCertificateChain or getKeyPair. */
    public <T> T keystore(String method, String alias, Class<T> type) throws KeystoreException {
        recordCall("KeystoreService", method, alias);
        KeyStore ks = loadKeystore();
        try {
            if (alias == null || !ks.containsAlias(alias)) {
                throw new KeystoreException("Alias " + alias + " not found in the simulator's keystore ("
                        + keystoreConfig.get("file") + ")");
            }
            Object r;
            switch (method) {
                case "getCertificate":
                    r = ks.getCertificate(alias);
                    break;
                case "getCertificateChain": {
                    Certificate[] chain = ks.getCertificateChain(alias);
                    Certificate c = ks.getCertificate(alias);
                    r = chain != null ? chain : c == null ? null : new Certificate[] {c};
                    break;
                }
                case "getKey":
                    r = key(ks, alias);
                    break;
                case "getKeyPair": {
                    Key k = key(ks, alias);
                    Certificate c = ks.getCertificate(alias);
                    if (!(k instanceof PrivateKey) || c == null) {
                        throw new KeystoreException("Alias " + alias + " is not a key pair entry");
                    }
                    r = new KeyPair(c.getPublicKey(), (PrivateKey) k);
                    break;
                }
                default:
                    throw new IllegalArgumentException(method);
            }
            return type.cast(r);
        } catch (java.security.GeneralSecurityException e) {
            throw new KeystoreException("Keystore access for alias " + alias + " failed: " + e.getMessage(), e);
        }
    }

    private Key key(KeyStore ks, String alias) throws java.security.GeneralSecurityException, KeystoreException {
        Map<String, Object> kp = Json.obj(keystoreConfig, "keyPasswords");
        String pw = kp != null && kp.get(alias) != null ? kp.get(alias).toString() : Json.str(keystoreConfig, "password", "");
        Key k = ks.getKey(alias, pw.toCharArray());
        if (k == null) {
            throw new KeystoreException("Alias " + alias + " is a certificate entry without a key");
        }
        return k;
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
        return r;
    }
}
