package io.cpiwb.groovy;

import io.cpiwb.core.Json;
import io.cpiwb.groovy.camel.Launcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs on both engines (groovy-v1 and groovy-v2 modules share this source). */
@SuppressWarnings("unchecked")
class ScriptRunnerTest {

    static final GroovyEngine ENGINE = Launcher.engine();
    static final boolean V2 = "groovy-2.x".equals(ENGINE.describe().get("profile"));
    static final String IMPORT = V2 ? "import com.sap.it.script.v2.api.Message\n"
            : "import com.sap.gateway.ip.core.customdev.util.Message\n";

    static Map<String, Object> run(String script, Map<String, Object> input, Map<String, Object> extra) throws Exception {
        Json.Obj req = Json.o().with("script", Json.o().with("content", script).with("path", "scripts/test.groovy"))
                .with("input", input);
        if (extra != null) {
            req.putAll(extra);
        }
        return (Map<String, Object>) ENGINE.call("groovy.run", req);
    }

    static Map<String, Object> in(String body, List<Object> headers, List<Object> props) {
        return Json.o().with("body", body).with("headers", headers).with("properties", props);
    }

    static Map<String, Object> hv(String name, String value, String type) {
        return Json.o().with("name", name).with("value", value).with("type", type);
    }

    static Map<String, Object> out(Map<String, Object> r) {
        return Json.obj(r, "output");
    }

    static Map<String, Object> entry(Map<String, Object> r, String list, String name) {
        for (Object o : Json.arr(out(r), list)) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (name.equals(m.get("name"))) {
                return m;
            }
        }
        return null;
    }

    @Test
    void modifiesBodyHeadersAndProperties() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def body = message.getBody(String)\n"
                + "  message.setBody(body.toUpperCase())\n"
                + "  message.setHeader('Count', 3)\n"
                + "  message.setProperty('Seen', message.getHeaders().get('In') + '!')\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("<a>x</a>", List.of(hv("In", "hello", "String")), List.of()), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("<A>X</A>", out(r).get("body"));
        assertEquals("Integer", entry(r, "headers", "Count").get("type"));
        assertEquals("hello!", entry(r, "properties", "Seen").get("value"));
        assertEquals(List.of("Count"), Json.obj(Json.obj(r, "changes"), "headers").get("added"));
    }

    @Test
    void camelTypeConversionAndCaseInsensitiveHeaders() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def bytes = message.getBody(byte[])\n"
                + "  def reader = message.getBody(java.io.Reader)\n"
                + "  message.setProperty('len', bytes.length)\n"
                + "  message.setProperty('asInt', message.getHeader('Num', Integer))\n"
                + "  message.setProperty('ci', message.getHeaders().get('content-type'))\n"
                + "  message.setProperty('size', message.getBodySize())\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("äb", List.of(hv("Num", "42", "String"), hv("Content-Type", "text/plain", "String")),
                List.of()), Json.o().with("options", Json.o().with("bodyType", "InputStream")));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("3", entry(r, "properties", "len").get("value"));
        assertEquals("Integer", entry(r, "properties", "asInt").get("type"));
        assertEquals("42", entry(r, "properties", "asInt").get("value"));
        assertEquals("text/plain", entry(r, "properties", "ci").get("value"));
    }

    @Test
    void messageLogAndConsole() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def log = messageLogFactory.getMessageLog(message)\n"
                + "  log?.setStringProperty('Order', '42')\n"
                + "  log?.addCustomHeaderProperty('PO', '4711')\n"
                + "  log?.addAttachmentAsString('Payload', message.getBody(String), 'text/plain')\n"
                + "  println 'from println'\n"
                + "  System.out.println('from System.out')\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("<x/>", List.of(), List.of()), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        Map<String, Object> mpl = Json.obj(r, "mpl");
        assertEquals(1, Json.arr(mpl, "properties").size());
        assertEquals("4711", ((Map<String, Object>) Json.arr(mpl, "customHeaders").get(0)).get("value"));
        assertEquals("<x/>", ((Map<String, Object>) Json.arr(mpl, "attachments").get(0)).get("content"));
        assertTrue(Json.str(r, "console", "").contains("from println"));
        assertTrue(Json.str(r, "console", "").contains("from System.out"));

        Map<String, Object> off = run(s, in("<x/>", List.of(), List.of()),
                Json.o().with("platform", Json.o().with("mplEnabled", false)));
        assertTrue(Json.bool(off, "ok", false), () -> Json.write(off));
        assertEquals(0, Json.arr(Json.obj(off, "mpl"), "attachments").size());
    }

    @Test
    void secureStoreAndValueMapping() throws Exception {
        String s = IMPORT
                + "import com.sap.it.api.ITApiFactory\n"
                + "import com.sap.it.api.securestore.SecureStoreService\n"
                + "import com.sap.it.api.mapping.ValueMappingApi\n"
                + "def Message processData(Message message) {\n"
                + "  def cred = ITApiFactory.getService(SecureStoreService, null).getUserCredential('SFTP')\n"
                + "  message.setProperty('user', cred.getUsername())\n"
                + "  message.setProperty('pass', new String(cred.getPassword()))\n"
                + "  def vm = ITApiFactory.getApi(ValueMappingApi, null)\n"
                + "  message.setProperty('plant', vm.getMappedValue('S4', 'Plant', '1000', 'Shop', 'Warehouse'))\n"
                + "  message.setProperty('none', vm.getMappedValue('S4', 'Plant', '9999', 'Shop', 'Warehouse'))\n"
                + "  return message\n}";
        Map<String, Object> platform = Json.o()
                .with("credentials", Json.o().with("SFTP", Json.o().with("username", "u1").with("password", "secret")))
                .with("valueMappings", List.of(Json.o().with("sourceAgency", "S4").with("sourceIdentifier", "Plant")
                        .with("sourceValue", "1000").with("targetAgency", "Shop").with("targetIdentifier", "Warehouse")
                        .with("targetValue", "WH-A")));
        Map<String, Object> r = run(s, in("", List.of(), List.of()), Json.o().with("platform", platform));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("u1", entry(r, "properties", "user").get("value"));
        assertEquals("secret", entry(r, "properties", "pass").get("value"));
        assertEquals("WH-A", entry(r, "properties", "plant").get("value"));
        assertNull(entry(r, "properties", "none"), "a null property value is not stored by Camel");
        assertTrue(Json.arr(r, "apiCalls").size() >= 4);

        Map<String, Object> missing = run(s, in("", List.of(), List.of()), Json.o().with("platform", Json.o()));
        assertFalse(Json.bool(missing, "ok", true));
        assertTrue(Json.write(missing).contains("SecureStoreException"), () -> Json.write(missing));
    }

    @Test
    void compileErrorWithLine() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n  def x = \n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("compile", r.get("failedPhase"));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertTrue(((Number) d.get("line")).intValue() >= 3, () -> Json.write(d));
    }

    @Test
    void runtimeErrorPointsToScriptLine() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def x = null\n"
                + "  x.foo()\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertEquals("runtime", r.get("failedPhase"));
        Map<String, Object> d = (Map<String, Object>) Json.arr(r, "diagnostics").get(0);
        assertEquals(4L, ((Number) d.get("line")).longValue(), () -> Json.write(r));
        assertTrue(Json.str(d, "message", "").contains("NullPointerException"));
    }

    @Test
    void missingFunction() throws Exception {
        String s = IMPORT + "def Message other(Message message) { message }";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertFalse(Json.bool(r, "ok", true));
        assertTrue(Json.write(r).contains("Function 'processData'"));
        Map<String, Object> custom = run(s, in("", List.of(), List.of()), Json.o().with("function", "other"));
        assertTrue(Json.bool(custom, "ok", false), () -> Json.write(custom));
    }

    @Test
    void groovyVersionDifferences() throws Exception {
        // groovy.util.XmlSlurper was removed in Groovy 4 (moved to groovy.xml): a classic 1.x -> 2.x break.
        String legacy = IMPORT + "def Message processData(Message message) {\n"
                + "  def root = new XmlSlurper().parseText(message.getBody(String))\n"
                + "  message.setProperty('n', root.i.size())\n  return message\n}";
        Map<String, Object> r = run(legacy, in("<r><i/><i/></r>", List.of(), List.of()), null);
        if (V2) {
            assertFalse(Json.bool(r, "ok", true), () -> Json.write(r));
            assertTrue(Json.write(r).contains("XmlSlurper"));
        } else {
            assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
            assertEquals("2", entry(r, "properties", "n").get("value"));
        }
        String json = IMPORT + "import groovy.json.JsonSlurper\nimport groovy.json.JsonOutput\n"
                + "def Message processData(Message message) {\n"
                + "  def o = new JsonSlurper().parseText(message.getBody(String))\n"
                + "  message.setBody(JsonOutput.toJson([sum: o.items.sum()]))\n  return message\n}";
        Map<String, Object> j = run(json, in("{\"items\":[1,2,3]}", List.of(), List.of()), null);
        assertEquals("{\"sum\":6}", out(j).get("body"), () -> Json.write(j));
        assertEquals(V2 ? "4.0.29" : "2.4.21", ENGINE.describe().get("groovy"));
    }

    @Test
    void wildcardImportIsLinted() throws Exception {
        String s = "import com.sap.gateway.ip.core.customdev.util.Message\nimport groovy.xml.*\n"
                + "def processData(message) { message }";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertTrue(Json.write(r).contains("Wildcard import"), () -> Json.write(r));
    }

    @Test
    void partnerDirectoryAndKeystore(@TempDir Path tmp) throws Exception {
        Path platformDir = Files.createDirectories(tmp.resolve("platform"));
        Path p12 = Path.of(getClass().getResource("/platform/test-keystore.p12").toURI());
        Files.copy(p12, platformDir.resolve("ks.p12"));
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream ksIn = Files.newInputStream(p12)) {
            store.load(ksIn, "changeit".toCharArray());
        }
        Files.write(platformDir.resolve("partner.crt"), store.getCertificate("partner_cert").getEncoded());
        Files.writeString(platformDir.resolve("logo.bin"), "LOGO");
        Files.writeString(platformDir.resolve("partner-directory.json"), "{\"partners\": {\"P1\": {"
                + "\"parameters\": {\"ADDRESS\": \"https://p1.example\", \"ADDR_2\": \"b\", \"ADDRXY\": \"c\","
                + " \"XSD\": {\"contentType\": \"text/xml\", \"file\": \"logo.bin\"},"
                + " \"CERT\": {\"contentType\": \"crt\", \"file\": \"partner.crt\"}},"
                + "\"authorizedUsers\": [\"sb-user-1\"],"
                + "\"alternativePartners\": [{\"agency\": \"SAP\", \"scheme\": \"GLN\", \"id\": \"400\"}]}}}");
        Files.writeString(platformDir.resolve("keystore.json"), "{\"file\": \"ks.p12\", \"password\": \"changeit\"}");
        String s = IMPORT + "import com.sap.it.api.ITApiFactory\n"
                + "import com.sap.it.api.pd.PartnerDirectoryService\n"
                + "import com.sap.it.api.pd.BinaryData\n"
                + "import com.sap.it.api.keystore.KeystoreService\n"
                + "def Message processData(Message message) {\n"
                + "  def pd = ITApiFactory.getService(PartnerDirectoryService, null)\n"
                + "  def pid = pd.getPartnerId('SAP', 'GLN', '400')\n"
                + "  message.setProperty('pid', pid)\n"
                + "  message.setProperty('alt', pd.getAlternativePartnerId('SAP', 'GLN', pid))\n"
                + "  message.setProperty('address', pd.getParameter('ADDRESS', pid, String))\n"
                + "  BinaryData b = pd.getParameter('XSD', pid, BinaryData)\n"
                + "  message.setProperty('bin', new String(b.getData(), 'UTF-8') + ' ' + b.getContentType())\n"
                + "  message.setProperty('missing', String.valueOf(pd.getParameter('NOPE', pid, String)))\n"
                + "  message.setProperty('like', pd.getParameterIdsMatchingPattern('ADDR\\\\_%', pid, String).join(','))\n"
                + "  message.setProperty('strings', pd.getParameters(pid, String).keySet().join(','))\n"
                + "  message.setProperty('pdCert', pd.getCertificateParameter('CERT', pid).getSubjectX500Principal().getName())\n"
                + "  message.setProperty('owner', pd.getPartnerIdOfAuthorizedUser('sb-user-1'))\n"
                + "  message.setProperty('users', pd.getAuthorizedUsers(pid).join(','))\n"
                + "  try { pd.getParameter('ADDRESS', pid, Integer) } catch (IllegalArgumentException e) { message.setProperty('badType', 'IAE') }\n"
                + "  def ks = ITApiFactory.getService(KeystoreService, null)\n"
                + "  message.setProperty('alg', ks.getKeyPair('sim_key').getPrivate().getAlgorithm())\n"
                + "  message.setProperty('cert', ks.getCertificate('partner_cert').getSubjectX500Principal().getName())\n"
                + "  message.setProperty('chain', ks.getCertificateChain('sim_key').length)\n"
                + "  message.setProperty('noKey', String.valueOf(ks.getKey('partner_cert')))\n"
                + "  message.setProperty('unknown', String.valueOf(ks.getCertificate('nope')))\n"
                + "  message.setProperty('km', ks.getKeyManagers('sim_key').length)\n"
                + "  message.setProperty('tm', ks.getTrustManager().getAcceptedIssuers().length > 0)\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), Json.o().with("baseDir", tmp.toString()));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("P1", entry(r, "properties", "pid").get("value"));
        assertEquals("400", entry(r, "properties", "alt").get("value"));
        assertEquals("https://p1.example", entry(r, "properties", "address").get("value"));
        assertEquals("LOGO text/xml", entry(r, "properties", "bin").get("value"));
        assertEquals("null", entry(r, "properties", "missing").get("value"));
        assertEquals("ADDR_2", entry(r, "properties", "like").get("value"), "escaped _ matches only itself");
        assertEquals("ADDRESS,ADDR_2,ADDRXY", entry(r, "properties", "strings").get("value"));
        assertEquals("CN=cpiwb test", entry(r, "properties", "pdCert").get("value"));
        assertEquals("P1", entry(r, "properties", "owner").get("value"));
        assertEquals("sb-user-1", entry(r, "properties", "users").get("value"));
        assertEquals("IAE", entry(r, "properties", "badType").get("value"));
        assertEquals("RSA", entry(r, "properties", "alg").get("value"));
        assertEquals("CN=cpiwb test", entry(r, "properties", "cert").get("value"));
        assertEquals("1", entry(r, "properties", "chain").get("value"));
        assertEquals("null", entry(r, "properties", "noKey").get("value"), "a certificate entry has no key");
        assertEquals("null", entry(r, "properties", "unknown").get("value"), "SAP documents null for an unknown alias");
        assertEquals("1", entry(r, "properties", "km").get("value"));
        assertEquals("true", entry(r, "properties", "tm").get("value"));
    }

    @Test
    void oauthTokensNumberRangesAndUnknownServices() throws Exception {
        String s = IMPORT + "import com.sap.it.api.ITApiFactory\n"
                + "import com.sap.it.api.securestore.SecureStoreService\n"
                + "import com.sap.it.api.nrc.NumberRangeConfigurationService\n"
                + "import com.sap.it.api.exception.InvalidContextException\n"
                + "def Message processData(Message message) {\n"
                + "  def t = ITApiFactory.getService(SecureStoreService, null).getAccesTokenForOauth2ClientCredential('OAUTH')\n"
                + "  message.setProperty('token', t.getAccessToken() + ' ' + t.getTokenResponse().get('access_token'))\n"
                + "  def nr = ITApiFactory.getService(NumberRangeConfigurationService, null)\n"
                + "  def a = nr.getNextValuefromNumberRange('INV', 'c1')\n"
                + "  def b = nr.getNextValuefromNumberRange('INV', 'c1')\n"
                + "  def c = nr.getNextValuefromNumberRange('INV', 'c2')\n"
                + "  def d = nr.getNextValuefromNumberRange('INV', 'c3')\n"
                + "  message.setProperty('numbers', [a, b, c, d].join(','))\n"
                + "  try { ITApiFactory.getService(com.sap.it.api.ITApi, null) }"
                + " catch (InvalidContextException e) { message.setProperty('invalid', e.getMessage()) }\n"
                + "  return message\n}";
        Map<String, Object> platform = Json.o()
                .with("credentials", Json.o().with("OAUTH", Json.o().with("accessToken", "tok-1")))
                .with("numberRanges", Json.o().with("INV", Json.o().with("next", 98).with("min", 1).with("max", 99)
                        .with("fieldLength", 4).with("rotate", true)));
        Map<String, Object> r = run(s, in("", List.of(), List.of()), Json.o().with("platform", platform));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("tok-1 tok-1", entry(r, "properties", "token").get("value"));
        assertEquals("0098,0098,0099,0001", entry(r, "properties", "numbers").get("value"));
        assertTrue(String.valueOf(entry(r, "properties", "invalid").get("value")).startsWith("UnsupportedInSimulator"),
                () -> Json.write(r));
        long secureStoreCalls = Json.arr(r, "apiCalls").stream().filter(c -> "SecureStoreService".equals(((Map<?, ?>) c).get("api"))).count();
        assertEquals(1, secureStoreCalls, () -> Json.write(r));
    }

    @Test
    void soapHeadersItApiAndLibraryJars(@TempDir Path tmp) throws Exception {
        // A jar in the iFlow's src/main/resources/lib is on the script's class path.
        Path src = Files.createDirectories(tmp.resolve("src/demo"));
        Files.writeString(src.resolve("Util.java"), "package demo; public class Util { public static String hi() { return \"hi\"; } }");
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), src.resolve("Util.java").toString());
        assertEquals(0, rc);
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path lib = Files.createDirectories(project.resolve("iflows/Flow/src/main/resources/lib"));
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(Files.newOutputStream(lib.resolve("util.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("demo/Util.class"));
            jar.write(Files.readAllBytes(classes.resolve("demo/Util.class")));
            jar.closeEntry();
        }
        String soap = V2 ? "" : "  def received = message.getSoapHeaders()\n"
                + "  message.setProperty('soapIn', received.size() + ':' + received[0].getQname().getLocalPart()"
                + " + ':' + received[0].getElement().getAttribute('id'))\n"
                + "  message.clearSoapHeaders()\n"
                + "  message.setSoapHeaders([new SoapHeader(new QName('urn:x', 'Out'), '<Out xmlns=\"urn:x\">1</Out>', true, null)])\n";
        String s = IMPORT + "import com.sap.gateway.ip.core.customdev.util.SoapHeader\n"
                + "import com.sap.it.api.ITApi\n"
                + "import javax.xml.namespace.QName\n"
                + "def Message processData(Message message) {\n"
                + soap
                + "  message.setProperty('lib', demo.Util.hi())\n"
                + "  message.setProperty('libFrom', demo.Util.protectionDomain.codeSource.location.toString())\n"
                + "  message.setProperty('itapi', ITApi.isInterface())\n"
                + "  return message\n}";
        Map<String, Object> input = in("", List.of(), List.of());
        input.put("soapHeaders", List.of(Json.o().with("name", "{urn:y}In").with("value", "<In xmlns=\"urn:y\" id=\"7\"/>")));
        Map<String, Object> r = (Map<String, Object>) ENGINE.call("groovy.run", Json.o()
                .with("script", Json.o().with("content", s).with("path", "iflows/Flow/src/main/resources/script/s.groovy"))
                .with("baseDir", project.toString()).with("input", input));
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("hi", entry(r, "properties", "lib").get("value"));
        // Loaded from a copy: the project's jar stays unlocked (Windows) and can be replaced or deleted.
        String libFrom = String.valueOf(entry(r, "properties", "libFrom").get("value"));
        assertFalse(java.nio.file.Paths.get(java.net.URI.create(libFrom)).startsWith(project), libFrom);
        assertEquals("true", entry(r, "properties", "itapi").get("value"));
        if (V2) {
            // The v2 Message has no SOAP header getter, so the input headers are not handed over.
            assertNull(r.get("soapHeaders"));
            assertTrue(Json.write(r).contains("has no getSoapHeaders()"), () -> Json.write(r));
        } else {
            assertEquals("1:In:7", entry(r, "properties", "soapIn").get("value"));
            Map<String, Object> out = (Map<String, Object>) Json.arr(r, "soapHeaders").get(0);
            assertEquals("{urn:x}Out", out.get("name"));
            assertEquals("<Out xmlns=\"urn:x\">1</Out>", out.get("value"));
            assertEquals(true, out.get("mustUnderstand"));
        }
    }

    @Test
    void attachmentsFollowThePublishedApi() throws Exception {
        String att = V2 ? "import com.sap.it.script.v2.api.attachment.DefaultAttachment as Att\n"
                : "import com.sap.gateway.ip.core.customdev.util.AttachmentWrapper as Att\n";
        String objects = V2 ? "getAttachmentObjects" : "getAttachmentWrapperObjects";
        String s = IMPORT + att + "import javax.activation.DataHandler\n"
                + "def Message processData(Message message) {\n"
                + "  def a = new Att(new DataHandler('hello', 'text/plain'))\n"
                + "  message.addAttachmentObject('a.txt', a)\n"
                + "  message.addAttachmentHeader('X-Tag', '1', a)\n"
                + "  message.addAttachmentHeader('x-tag', '2', a)\n"
                + "  message.getAttachments().put('b.txt', new DataHandler('bb', 'text/plain'))\n"
                + "  message.setProperty('tag', message.getAttachmentHeader('X-TAG', a) + '|' + a.getHeaderNames())\n"
                + "  message.setProperty('names', message." + objects + "().keySet().join(','))\n"
                + "  message.setProperty('size', message.getAttachmentsSize())\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("1,2|[x-tag]", entry(r, "properties", "tag").get("value"));
        assertEquals("a.txt,b.txt", entry(r, "properties", "names").get("value"));
        assertEquals("7", entry(r, "properties", "size").get("value"));
        assertEquals(2, Json.arr(r, "attachments").size());
    }

    @Test
    void migrationHintsBetweenScriptVersions() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def x = new XmlSlurper().parseText('<a/>')\n"
                + "  return message\n}";
        Map<String, Object> r = run(s, in("", List.of(), List.of()), null);
        String json = Json.write(r);
        if (V2) {
            assertFalse(Json.bool(r, "ok", true));
            assertTrue(json.contains("add 'import groovy.xml.XmlSlurper'"), json);
        } else {
            assertTrue(Json.bool(r, "ok", false), json);
            assertTrue(json.contains("Groovy Script 2.x (Groovy 4) only has it in groovy.xml"), json);
        }
        if (V2) {
            String v1Only = IMPORT + "def Message processData(Message message) {\n"
                    + "  message.getSoapHeaders()\n"
                    + "  return message\n}";
            Map<String, Object> e = run(v1Only, in("", List.of(), List.of()), null);
            String ej = Json.write(e);
            assertFalse(Json.bool(e, "ok", true));
            assertTrue(ej.contains("not of SAP's published v2 interface"), ej);
            assertTrue(ej.contains("Script API v1 Message only"), ej);

            String ext = IMPORT + "import com.sap.it.script.v2.api.extensions.ExchangeExtensionFunctions\n"
                    + "import com.sap.it.script.v2.api.extensions.ProcessDirectService\n"
                    + "def Message processData(Message message) {\n"
                    + "  def f = message.getExtensionFunctions(ExchangeExtensionFunctions)\n"
                    + "  message.setProperty('created', f.getCreated() > 0)\n"
                    + "  message.setProperty('h', message.getHeader('missing', 'dflt'))\n"
                    + "  message.getExtensionFunctions(ProcessDirectService).invokeProcessDirect('/x', null)\n"
                    + "  return message\n}";
            Map<String, Object> x = run(ext, in("", List.of(), List.of()), null);
            assertTrue(Json.write(x).contains("UnsupportedInSimulator: ProcessDirectService"), () -> Json.write(x));
        }
    }

    @Test
    void inputAttachments() throws Exception {
        String s = IMPORT + "def Message processData(Message message) {\n"
                + "  def a = message.getAttachments()\n"
                + "  message.setProperty('names', a.keySet().join(','))\n"
                + "  message.setProperty('text', a['note.txt'].getContent().text)\n"
                + "  message.setProperty('type', a['logo.bin'].getContentType())\n"
                + "  message.setProperty('size', message.getAttachmentsSize())\n"
                + "  return message\n}";
        Map<String, Object> input = in("", List.of(), List.of());
        input.put("attachments", List.of(
                Json.o().with("name", "note.txt").with("contentType", "text/plain").with("text", "hallo"),
                Json.o().with("name", "logo.bin").with("contentType", "image/png").with("base64", "AAEC")));
        Map<String, Object> r = run(s, input, null);
        assertTrue(Json.bool(r, "ok", false), () -> Json.write(r));
        assertEquals("note.txt,logo.bin", entry(r, "properties", "names").get("value"));
        assertEquals("hallo", entry(r, "properties", "text").get("value"));
        assertEquals("image/png", entry(r, "properties", "type").get("value"));
        assertEquals("8", entry(r, "properties", "size").get("value"));
    }
}
