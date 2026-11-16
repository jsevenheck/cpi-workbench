package io.cpiwb.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The iFlow model of the synthetic fixture, shaped as public exports are (plan F68). */
@SuppressWarnings("unchecked")
class IflowModelTest {

    private static Map<String, Object> model(Path tmp) throws Exception {
        Path fixture = Path.of(IflowModelTest.class.getResource("/iflow").toURI());
        Path target = tmp.resolve("project/iflows/Order_Replication");
        try (Stream<Path> s = Files.walk(fixture)) {
            for (Path p : s.toList()) {
                Path t = target.resolve(fixture.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
        List<Object> models = (List<Object>) IflowModel.models(tmp.resolve("project")).get("models");
        assertEquals(1, models.size());
        return (Map<String, Object>) models.get(0);
    }

    private static Map<String, Map<String, Object>> byId(Map<String, Object> m, String key) {
        return ((List<Object>) m.get(key)).stream().map(o -> (Map<String, Object>) o)
                .collect(Collectors.toMap(o -> (String) o.get("id"), o -> o, (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Test
    void processesNodesFlowsAndLayout(@TempDir Path tmp) throws Exception {
        Map<String, Object> m = model(tmp);
        assertEquals("Order_Replication", m.get("iflow"));
        assertEquals("iflows/Order_Replication/src/main/resources/scenarioflows/integrationflow/Order_Replication.iflw", m.get("file"));
        assertEquals(true, m.get("hasLayout"));

        Map<String, Map<String, Object>> processes = byId(m, "processes");
        assertEquals(List.of("Process_1", "SubProcess_1", "Process_2"), List.copyOf(processes.keySet()));
        assertEquals("main", processes.get("Process_1").get("kind"));
        assertEquals("Participant_Process_1", processes.get("Process_1").get("pool"));
        assertEquals("exception", processes.get("SubProcess_1").get("kind"));
        assertEquals("Process_1", processes.get("SubProcess_1").get("parent"));
        assertEquals("local", processes.get("Process_2").get("kind"));
        assertEquals(1800.0, ((Map<String, Object>) processes.get("Process_1").get("bounds")).get("width"));

        Map<String, Map<String, Object>> nodes = byId(m, "nodes");
        assertEquals("sender", nodes.get("Participant_1").get("kind"));
        assertEquals("receiver", nodes.get("Participant_2").get("kind"), "EndpointRecevier, as the editor spells it");
        assertFalse(nodes.containsKey("Participant_Process_1"), "a process pool is no node");
        Map<String, Object> call = nodes.get("CallActivity_13");
        assertEquals("Process_2", call.get("calls"));
        assertEquals("NonLoopingProcess", call.get("variant"));
        assertEquals("gateway", nodes.get("ExclusiveGateway_1").get("kind"));
        assertEquals("error", nodes.get("StartEvent_3").get("event"));
        assertEquals("SubProcess_1", nodes.get("StartEvent_3").get("process"));
        Map<String, Object> store = nodes.get("CallActivity_14");
        assertEquals("DBstorage", store.get("activityType"));
        assertEquals("orders", ((Map<String, Object>) store.get("properties")).get("storageName"));
        assertEquals(Map.of("x", 400.0, "y", 690.0, "width", 100.0, "height", 60.0), store.get("bounds"));
        // The process's own properties are not mixed into those of its elements, and the other way round.
        assertNull(((Map<String, Object>) nodes.get("CallActivity_3").get("properties")).get("transactionTimeout"));

        Map<String, Map<String, Object>> flows = byId(m, "flows");
        Map<String, Object> https = flows.get("MessageFlow_1");
        assertEquals("message", https.get("kind"));
        assertEquals("HTTPS", https.get("adapter"));
        assertEquals("Sender", https.get("direction"));
        assertEquals(true, flows.get("SequenceFlow_9").get("default"));
        assertNull(flows.get("SequenceFlow_8").get("default"));
        assertEquals("${header.SenderId} = 'ERP'", flows.get("SequenceFlow_8").get("condition"));
        assertEquals(3, ((List<Object>) flows.get("SequenceFlow_8").get("waypoints")).size());
        assertTrue(flows.containsKey("SequenceFlow_40"), "flows inside the exception subprocess");
    }

    @Test
    void anIflowWithoutLayoutOrThatCannotBeReadIsReported(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("iflows/A/src/main/resources/scenarioflows/integrationflow"));
        Files.writeString(dir.resolve("A.iflw"), "<bpmn2:definitions xmlns:bpmn2=\"http://www.omg.org/spec/BPMN/20100524/MODEL\">"
                + "<bpmn2:process id=\"P\"><bpmn2:startEvent id=\"S\"/></bpmn2:process></bpmn2:definitions>");
        Path broken = Files.createDirectories(tmp.resolve("iflows/B"));
        Files.writeString(broken.resolve("B.iflw"), "<not closed");
        List<Object> models = (List<Object>) IflowModel.models(tmp).get("models");
        Map<String, Object> a = (Map<String, Object>) models.get(0);
        assertEquals(false, a.get("hasLayout"));
        assertEquals("main", ((Map<String, Object>) ((List<Object>) a.get("processes")).get(0)).get("kind"));
        assertTrue(((Map<String, Object>) models.get(1)).get("error").toString().startsWith("Cannot read the iFlow model"));
    }

    @Test
    void theVariantIsTheNameInCmdVariantUri() {
        assertEquals("put", IflowModel.variant("ctype::FlowstepVariant/cname::put/version::1.6.0"));
        assertNull(IflowModel.variant("nothing"));
    }
}
