package io.cpiwb.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where-used search and parameter overview over two synthetic iFlows with adapter keys as in public exports (F69). */
@SuppressWarnings("unchecked")
class IflowIndexTest {

    private static Path project() throws Exception {
        return Path.of(IflowIndexTest.class.getResource("/iflow-index").toURI());
    }

    private static List<Map<String, Object>> hits(Map<String, Object> p) throws Exception {
        return ((List<Object>) IflowIndex.search(project(), p).get("hits")).stream()
                .map(o -> (Map<String, Object>) o).collect(Collectors.toList());
    }

    private static List<String> where(List<Map<String, Object>> hits) {
        return hits.stream().map(h -> h.get("iflow") + "/" + h.get("element") + "/" + h.get("key")).toList();
    }

    @Test
    void aSharedQueueIsFoundInBothIflows() throws Exception {
        List<Map<String, Object>> h = hits(Map.of("query", "orders_in"));
        assertEquals(List.of("Inbound_Files/MessageFlow_2/QueueName_outbound", "Outbound_Orders/MessageFlow_1/QueueName_inbound"), where(h));
        assertTrue(h.stream().allMatch(x -> "queue".equals(x.get("kind"))));
        // An adapter's hit selects the system at its outer end and is labelled with adapter, direction and, for a receiver, its caller.
        assertEquals("Participant_2", h.get(0).get("node"));
        assertEquals("Order queue", h.get(0).get("name"));
        assertEquals("JMS receiver from End", h.get(0).get("label")); // the step (here an end event) that calls it
        assertEquals("Participant_1", h.get(1).get("node"));
        assertEquals("JMS sender", h.get(1).get("label"));
    }

    @Test
    void kindsComeFromTheAdapterKeys() throws Exception {
        assertEquals(List.of("Inbound_Files/MessageFlow_1/credential_name", "Outbound_Orders/MessageFlow_2/credentialName"),
                where(hits(Map.of("kind", "credential"))));
        assertEquals(List.of("Inbound_Files/MessageFlow_1/host", "Inbound_Files/MessageFlow_1/path",
                "Outbound_Orders/MessageFlow_2/httpAddressWithoutQuery"), where(hits(Map.of("kind", "address"))));
        // JDBC's alias is a data source, not a credential as in OData: no kind, still found by value.
        List<Map<String, Object>> ds = hits(Map.of("query", "orders_ds"));
        assertEquals(1, ds.size());
        assertNull(ds.get(0).get("kind"));
        assertEquals(List.of(), hits(Map.of("query", "orders_ds", "kind", "credential")));
    }

    @Test
    void parametersKeysAndIflowFilter() throws Exception {
        // Steps first, then adapters and routes, per iFlow.
        assertEquals(List.of("Inbound_Files/CallActivity_1/headerTable", "Inbound_Files/MessageFlow_1/host",
                "Outbound_Orders/MessageFlow_2/httpAddressWithoutQuery"), where(hits(Map.of("kind", "parameter"))));
        assertEquals(List.of("Outbound_Orders/MessageFlow_2/httpAddressWithoutQuery"),
                where(hits(Map.of("kind", "parameter", "iflow", "Outbound_Orders"))));
        // A key name finds that property everywhere, ignoring case; metadata keys are not indexed.
        assertEquals(List.of("Inbound_Files/MessageFlow_1/path"), where(hits(Map.of("query", "PATH"))));
        assertEquals(List.of(), hits(Map.of("query", "AdapterVariant")));
        assertThrows(IllegalArgumentException.class, () -> IflowIndex.search(project(), Map.of()));
        // A route: its condition and its other settings.
        assertEquals(List.of("Outbound_Orders/SequenceFlow_2/condition"), where(hits(Map.of("query", "header.Region"))));
        assertEquals(List.of("Outbound_Orders/SequenceFlow_2/expressionType"), where(hits(Map.of("query", "expressionType"))));
    }

    @Test
    void parameterOverview() throws Exception {
        List<Object> iflows = (List<Object>) IflowIndex.index(project()).get("iflows");
        assertEquals(2, iflows.size());
        Map<String, Map<String, Object>> in = params((Map<String, Object>) iflows.get(0));
        assertEquals(List.of("Defined Only", "SftpHost", "Token 'Auth'", "Unused Param"), List.copyOf(in.keySet()));
        // Used in a Content Modifier table, where the export escapes the quote once more: still one parameter, with its value.
        Map<String, Object> token = in.get("Token 'Auth'");
        assertEquals(false, token.get("unused"));
        assertEquals(false, token.get("unresolved"));
        assertEquals("CallActivity_1", ((Map<String, Object>) ((List<Object>) token.get("uses")).get(0)).get("element"));
        Map<String, Object> host = in.get("SftpHost");
        assertEquals("sftp.partner.example.com:22", host.get("value"));
        assertEquals("xsd:string", host.get("type"));
        assertEquals(true, host.get("required"));
        assertEquals("Partner SFTP server", host.get("description"));
        assertEquals(2, ((List<Object>) host.get("uses")).size());
        assertEquals(false, host.get("unused"));
        assertEquals(true, in.get("Unused Param").get("unused"));
        assertNull(in.get("Unused Param").get("type"));
        Map<String, Object> defined = in.get("Defined Only");
        assertEquals(false, defined.get("required"));
        assertNull(defined.get("description"), "an empty description is none");
        assertEquals(true, defined.get("unused"));
        assertEquals(false, defined.get("unresolved"), "defined, but nothing uses it: no value is missing");

        Map<String, Map<String, Object>> out = params((Map<String, Object>) iflows.get(1));
        Map<String, Object> url = out.get("BackendUrl");
        assertEquals(true, url.get("unresolved"), "used, but the export has no parameters.prop value");
        assertEquals("MessageFlow_2", ((Map<String, Object>) ((List<Object>) url.get("uses")).get(0)).get("element"));
    }

    @Test
    void aBrokenParameterFileIsThatIflowsError(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path src = project();
        try (var files = java.nio.file.Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = dir.resolve(src.relativize(f).toString());
                if (java.nio.file.Files.isDirectory(f)) java.nio.file.Files.createDirectories(to);
                else java.nio.file.Files.copy(f, to);
            }
        }
        // An invalid \\u escape makes Properties.load throw.
        java.nio.file.Files.writeString(dir.resolve("iflows/Outbound_Orders/src/main/resources/parameters.prop"), "BackendUrl=\\uZZZZ\n");
        List<Object> iflows = (List<Object>) IflowIndex.index(dir).get("iflows");
        assertEquals(2, iflows.size());
        assertNull(((Map<String, Object>) iflows.get(0)).get("error"));
        assertEquals("sftp.partner.example.com:22", params((Map<String, Object>) iflows.get(0)).get("SftpHost").get("value"));
        Map<String, Object> broken = (Map<String, Object>) iflows.get(1);
        assertTrue(String.valueOf(broken.get("error")).startsWith("Cannot read the parameters"), String.valueOf(broken.get("error")));
        assertEquals(List.of(), broken.get("parameters"));
    }

    private static Map<String, Map<String, Object>> params(Map<String, Object> iflow) {
        return ((List<Object>) iflow.get("parameters")).stream().map(o -> (Map<String, Object>) o)
                .collect(Collectors.toMap(p -> (String) p.get("name"), p -> p, (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
