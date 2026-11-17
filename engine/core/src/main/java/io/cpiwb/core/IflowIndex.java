package io.cpiwb.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where-used and parameter overview over the imported iFlows ({@code iflow.index}, {@code iflow.search}): every
 * property value of every element, adapter and route, the files the steps use and the externalized parameters. It
 * reads the exports only (the same reading as {@link IflowModel} and {@link IflowImport}); nothing here is CPI
 * behaviour, and nothing connects to a tenant. The app's Where used tab and the MCP tool {@code iflow_where_used}
 * both call it, so they cannot disagree.
 */
public final class IflowIndex {

    /** Externalized parameters in property values: {@code {{name}}}. */
    private static final Pattern PARAM = Pattern.compile("\\{\\{([^{}]+)}}");
    /** Keys that describe the editor's component, not the configuration: left out of the index. */
    private static final Set<String> SKIPPED = Set.of("cmdVariantUri", "componentVersion", "componentNS", "componentSWCVName",
            "componentSWCVId");

    private IflowIndex() {
    }

    /**
     * The kind of an adapter property by its key, where public exports show what the key holds (docs/plan.md F69): an
     * address (host, URL, path), a credential alias, a queue or a key alias. The same key means different things in
     * different adapters ({@code alias} is a credential in OData and SuccessFactors, a data source in JDBC), so the
     * table is per adapter. Keys not listed are searched like any other property.
     */
    static String kindOf(String adapter, String key) {
        Map<String, String> keys = ADAPTER_KEYS.get(adapter);
        return keys == null ? null : keys.get(key);
    }

    private static final String ADDRESS = "address", CREDENTIAL = "credential", QUEUE = "queue", KEY = "key";

    /** Adapter (ComponentType) to property key to kind, from 384 iFlows of SAP's integration recipes and 59 others (F69). */
    private static final Map<String, Map<String, String>> ADAPTER_KEYS = Map.ofEntries(
            Map.entry("SFTP", Map.of("host", ADDRESS, "path", ADDRESS, "credential_name", CREDENTIAL, "privateKeyAlias", KEY)),
            Map.entry("PollingSFTP", Map.of("host", ADDRESS, "path", ADDRESS, "credential_name", CREDENTIAL, "privateKeyAlias", KEY)),
            Map.entry("FTP", Map.of("host", ADDRESS, "path", ADDRESS, "credential_name", CREDENTIAL)),
            Map.entry("HTTPS", Map.of("urlPath", ADDRESS)),
            Map.entry("HTTP", Map.of("httpAddressWithoutQuery", ADDRESS, "credentialName", CREDENTIAL, "privateKeyAlias", KEY)),
            Map.entry("SOAP", Map.ofEntries(Map.entry("address", ADDRESS), Map.entry("soapWsdlURL", ADDRESS),
                    Map.entry("credentialName", CREDENTIAL), Map.entry("UserNameTokenCredentialName", CREDENTIAL),
                    Map.entry("WsdlUserNameTokenCredentialName", CREDENTIAL), Map.entry("privateKeyAlias", KEY),
                    Map.entry("PrivateKeyAliasSigning", KEY), Map.entry("PublicKeyAliasEncryption", KEY),
                    Map.entry("PrivateKeyAliasResponseSigning", KEY), Map.entry("PublicKeyAliasResponseEncryption", KEY))),
            Map.entry("HCIOData", Map.of("address", ADDRESS, "alias", CREDENTIAL, "odataCertAuthPrivateKeyAlias", KEY)),
            Map.entry("SuccessFactors", Map.of("address", ADDRESS, "alias", CREDENTIAL)),
            Map.entry("JMS", Map.of("QueueName_inbound", QUEUE, "QueueName_outbound", QUEUE)),
            Map.entry("ProcessDirect", Map.of("address", ADDRESS)),
            Map.entry("Mail", Map.of("server", ADDRESS, "user", CREDENTIAL, "tokenCredential", CREDENTIAL)),
            Map.entry("IDOC", Map.of("address", ADDRESS, "credentialName", CREDENTIAL, "privateKeyAlias", KEY)),
            Map.entry("AS2", Map.ofEntries(Map.entry("address", ADDRESS), Map.entry("receipientURL", ADDRESS),
                    Map.entry("mdnTargetURL", ADDRESS), Map.entry("mdnUserCredentialAlias", CREDENTIAL),
                    Map.entry("userCredentialAlias", CREDENTIAL), Map.entry("privateKeyAlias", KEY),
                    Map.entry("privateKeyAliasForDecryption", KEY), Map.entry("privateKeyAliasForMDNSigning", KEY),
                    Map.entry("mdnPrivateKeyAlias", KEY), Map.entry("publicKeyAliasForVerification", KEY),
                    Map.entry("privateKeyAliasForSigning", KEY), Map.entry("publicKeyAliasForEncryption", KEY),
                    Map.entry("publicKeyAliasForMDNVerification", KEY))),
            Map.entry("AMQP", Map.of("host", ADDRESS, "path", ADDRESS, "credentialName", CREDENTIAL, "destinationName", QUEUE)),
            Map.entry("RFC", Map.of("destination", ADDRESS)),
            Map.entry("XI", Map.of("Address", ADDRESS, "Address_inbound", ADDRESS, "BasicAuthCredentialName", CREDENTIAL,
                    "ClientCertificateAlias", KEY)),
            Map.entry("AdvancedEventMesh", Map.of("host", ADDRESS, "queueName", QUEUE, "topicEndpointName", QUEUE,
                    "destinationName", QUEUE)));

    /** {@code iflow.index}: per iFlow its entries and parameters. */
    public static Map<String, Object> index(Path projectRoot) throws IOException {
        List<Object> out = new ArrayList<>();
        for (IflowEntries e : read(projectRoot)) {
            // A broken parameters.prop or .propdef is this iFlow's error; the other iFlows keep their parameters.
            List<Object> parameters = List.of();
            String error = e.error;
            try {
                parameters = parameters(e);
            } catch (IOException | RuntimeException x) {
                String msg = "Cannot read the parameters: " + x.getMessage();
                error = error == null ? msg : error + " " + msg;
            }
            out.add(Json.o().with("iflow", e.iflow).with("name", e.name).with("file", e.file).with("error", error)
                    .with("entries", e.entries).with("parameters", parameters));
        }
        return Json.o().with("iflows", out);
    }

    /**
     * {@code iflow.search}: entries whose value contains {@code query} or whose key is {@code query} (both ignoring
     * case), optionally only of one {@code kind} (address, credential, queue, key, file, parameter) or one {@code iflow}.
     */
    public static Map<String, Object> search(Path projectRoot, Map<String, Object> p) throws IOException {
        String query = Json.str(p, "query", "").trim();
        String kind = Json.str(p, "kind", null);
        String only = Json.str(p, "iflow", null);
        if (query.isEmpty() && kind == null) {
            throw new IllegalArgumentException("Give a query, a kind, or both.");
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Object> hits = new ArrayList<>();
        int iflows = 0;
        for (IflowEntries e : read(projectRoot)) {
            if (only != null && !only.equals(e.iflow)) {
                continue;
            }
            iflows++;
            for (Json.Obj entry : e.entries) {
                String value = String.valueOf(entry.get("value"));
                String key = String.valueOf(entry.get("key"));
                boolean ofKind = kind == null || kind.equals(entry.get("kind"))
                        || ("parameter".equals(kind) && PARAM.matcher(value).find());
                boolean matches = q.isEmpty() || value.toLowerCase(Locale.ROOT).contains(q) || key.equalsIgnoreCase(query);
                if (ofKind && matches) {
                    hits.add(entry);
                }
            }
        }
        return Json.o().with("query", query).with("kind", kind).with("iflows", iflows).with("hits", hits);
    }

    // ------------------------------------------------------------------ reading

    record IflowEntries(String iflow, String name, String file, String error, List<Json.Obj> entries, Path flowRoot) {
    }

    @SuppressWarnings("unchecked")
    static List<IflowEntries> read(Path projectRoot) throws IOException {
        Path iflows = projectRoot.resolve("iflows");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(iflows)) {
            try (Stream<Path> s = Files.walk(iflows, 12)) {
                s.filter(f -> f.toString().endsWith(".iflw")).sorted().forEach(files::add);
            }
        }
        // The files the script, mapping and schema steps use, as iflow.steps resolves them (also script collections).
        Map<String, Object> fileOf = new LinkedHashMap<>();
        for (Object o : (List<Object>) IflowImport.steps(projectRoot).get("steps")) {
            Map<String, Object> s = (Map<String, Object>) o;
            if (s.get("file") != null) {
                fileOf.put(s.get("iflow") + "\u0000" + s.get("id"), s.get("file"));
            }
        }
        List<IflowEntries> out = new ArrayList<>();
        for (Path file : files) {
            Path rel = iflows.relativize(file);
            String iflow = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
            Path flowRoot = iflows.resolve(iflow);
            String path = projectRoot.relativize(file).toString().replace('\\', '/');
            List<Json.Obj> entries = new ArrayList<>();
            try {
                Json.Obj model = IflowModel.read(file, Integer.MAX_VALUE);
                entries(iflow, model, fileOf, entries);
                out.add(new IflowEntries(iflow, (String) model.get("name"), path, null, entries, flowRoot));
            } catch (Exception ex) {
                out.add(new IflowEntries(iflow, null, path, "Cannot read the iFlow model: " + ex.getMessage(), entries, flowRoot));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void entries(String iflow, Json.Obj model, Map<String, Object> fileOf, List<Json.Obj> out) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Object o : (List<Object>) model.get("nodes")) {
            Map<String, Object> n = (Map<String, Object>) o;
            nodes.put((String) n.get("id"), n);
        }
        for (Map<String, Object> n : nodes.values()) {
            String id = (String) n.get("id");
            String type = String.valueOf(n.get("kind"));
            String label = n.get("activityType") != null ? (String) n.get("activityType") : type;
            ((Map<String, Object>) n.get("properties")).forEach((k, v) ->
                    add(out, iflow, id, id, (String) n.get("name"), type, label, k, String.valueOf(v), null));
            Object file = fileOf.get(iflow + "\u0000" + id);
            if (file != null) {
                add(out, iflow, id, id, (String) n.get("name"), type, label, "file", (String) file, "file");
            }
        }
        for (Object o : (List<Object>) model.get("flows")) {
            Map<String, Object> f = (Map<String, Object>) o;
            String id = (String) f.get("id");
            if ("message".equals(f.get("kind"))) {
                // An adapter: the diagram shows the system at its outer end (the sender's source, the receiver's target).
                String adapter = String.valueOf(f.get("adapter"));
                boolean receiver = "Receiver".equalsIgnoreCase(String.valueOf(f.get("direction")));
                String system = (String) (receiver ? f.get("target") : f.get("source"));
                String node = nodes.containsKey(system) ? system : (String) (receiver ? f.get("source") : f.get("target"));
                String name = nodes.containsKey(system) ? (String) nodes.get(system).get("name") : (String) f.get("name");
                ((Map<String, Object>) f.get("properties")).forEach((k, v) ->
                        add(out, iflow, id, node, name, "adapter", adapter + (receiver ? " receiver" : " sender"), k,
                                String.valueOf(v), kindOf(adapter, k)));
            } else if (f.get("condition") != null) {
                // A Router's route: the gateway it leaves.
                String gateway = (String) f.get("source");
                String name = (String) f.get("name");
                String shown = name == null || name.isEmpty() ? id : name;
                add(out, iflow, id, gateway, shown, "route", "Route", "condition", (String) f.get("condition"), null);
                ((Map<String, Object>) f.get("properties")).forEach((k, v) ->
                        add(out, iflow, id, gateway, shown, "route", "Route", k, String.valueOf(v), null));
            }
        }
    }

    private static void add(List<Json.Obj> out, String iflow, String element, String node, String name, String type,
                            String label, String key, String value, String kind) {
        if (SKIPPED.contains(key) || value == null || value.isEmpty()) {
            return;
        }
        out.add(Json.o().with("iflow", iflow).with("element", element).with("node", node).with("name", name)
                .with("type", type).with("label", label).with("key", key).with("value", value).with("kind", kind));
    }

    // ------------------------------------------------------------------ parameters

    /**
     * The iFlow's externalized parameters: values from {@code parameters.prop}, type, required flag and description
     * from {@code parameters.propdef} (F69), every {@code {{name}}} use, and which ones are unused (a value nothing
     * uses) or unresolved (used without a value).
     */
    private static List<Object> parameters(IflowEntries e) throws IOException {
        Map<String, Map<String, String>> defs = definitions(e.flowRoot.resolve("src/main/resources/parameters.propdef"));
        Properties values = new Properties();
        Path prop = e.flowRoot.resolve("src/main/resources/parameters.prop");
        if (Files.isRegularFile(prop)) {
            try (InputStream in = Files.newInputStream(prop)) {
                values.load(in);
            }
        }
        Map<String, List<Object>> uses = new TreeMap<>();
        for (Json.Obj entry : e.entries) {
            Matcher m = PARAM.matcher(String.valueOf(entry.get("value")));
            while (m.find()) {
                uses.computeIfAbsent(m.group(1).trim(), k -> new ArrayList<>()).add(Json.o().with("element", entry.get("element"))
                        .with("node", entry.get("node")).with("name", entry.get("name")).with("key", entry.get("key")));
            }
        }
        for (String name : values.stringPropertyNames()) {
            uses.putIfAbsent(name, new ArrayList<>());
        }
        for (String name : defs.keySet()) {
            uses.putIfAbsent(name, new ArrayList<>());
        }
        List<Object> out = new ArrayList<>();
        uses.forEach((name, u) -> {
            Map<String, String> d = defs.getOrDefault(name, Map.of());
            String description = d.get("description");
            out.add(Json.o().with("name", name).with("value", values.getProperty(name)).with("type", d.get("type"))
                    .with("required", d.containsKey("isRequired") ? "true".equals(d.get("isRequired")) : null)
                    .with("description", description == null || description.isEmpty() ? null : description)
                    .with("uses", u).with("unused", u.isEmpty()).with("unresolved", !u.isEmpty() && values.getProperty(name) == null));
        });
        return out;
    }

    /** {@code parameters.propdef}: {@code <parameters><parameter><name/><type/><isRequired/><description/>} (F69). */
    private static Map<String, Map<String, String>> definitions(Path file) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.NodeList params = f.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("parameter");
            for (int i = 0; i < params.getLength(); i++) {
                Map<String, String> d = new LinkedHashMap<>();
                for (org.w3c.dom.Node c = params.item(i).getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof org.w3c.dom.Element el) {
                        d.put(el.getTagName(), el.getTextContent().trim());
                    }
                }
                if (d.get("name") != null && !d.get("name").isEmpty()) {
                    out.put(d.get("name"), d);
                }
            }
        } catch (Exception ex) {
            // A definition file that cannot be read leaves values and uses as they are; the rest of the export stays usable.
            return new LinkedHashMap<>();
        }
        return out;
    }
}
