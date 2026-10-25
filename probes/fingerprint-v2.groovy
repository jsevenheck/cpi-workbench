// CPI Workbench – runtime fingerprint for a Groovy Script step version 2.x (Script API v2).
// The v2 interface follows SAP's published Script API (com.sap.cloud.script:script.v2.api on Maven
// Central); not yet run on a tenant. If it does not compile, the error message itself is useful.
import com.sap.it.script.v2.api.Message
import groovy.json.JsonOutput

def Message processData(Message message) {
    def safe = { Closure c -> try { c() } catch (Throwable t) { 'unavailable: ' + t.class.name } }
    def info = [
        probe          : 'fingerprint-v2',
        java           : System.getProperty('java.version'),
        javaVendor     : System.getProperty('java.vendor'),
        groovy         : safe { GroovySystem.version },
        camel          : safe { Class.forName('org.apache.camel.CamelContext').package?.implementationVersion
                                    ?: Class.forName('org.apache.camel.impl.DefaultCamelContext').getDeclaredConstructor().newInstance().version },
        camelPom           : safe { ['camel-core', 'camel-api', 'camel-core-engine'].collect { a ->
                                        def p = new Properties()
                                        def stream = Class.forName('org.apache.camel.CamelContext').getResourceAsStream("/META-INF/maven/org.apache.camel/${a}/pom.properties")
                                        if (stream) { p.load(stream); "${a} ${p.version}" } else null }.findAll() },
        saxon          : safe { Class.forName('net.sf.saxon.Version').getMethod('getProductTitle').invoke(null) },
        defaultCharset : java.nio.charset.Charset.defaultCharset().name(),
        timezone       : TimeZone.default.ID,
        locale         : Locale.default.toString(),
        messageClass   : message.class.name,
        // The v2 API surface is still unknown (docs/plan.md F6): list it by reflection.
        messageMethods : safe { message.class.methods.findAll { it.declaringClass != Object }
                                    .collect { it.name + '(' + it.parameterTypes*.simpleName.join(', ') + '): ' + it.returnType.simpleName }
                                    .unique().sort() },
        interfaces     : safe { message.class.interfaces*.name },
        // Which optional Groovy modules / libraries the runtime offers (the simulator bundles
        // groovy-xml, -json, -dateutil, -datetime, -templates, -nio for 2.x).
        classesAvailable   : ['groovy.util.XmlSlurper', 'groovy.xml.XmlSlurper', 'groovy.json.JsonSlurper',
                              'groovy.text.SimpleTemplateEngine', 'groovy.sql.Sql', 'groovy.yaml.YamlSlurper',
                              'groovy.toml.TomlSlurper', 'groovy.ant.AntBuilder', 'org.apache.commons.lang3.StringUtils',
                              'com.fasterxml.jackson.databind.ObjectMapper', 'org.apache.poi.ss.usermodel.Workbook',
                              'org.apache.commons.codec.binary.Base64', 'iaik.cms.EnvelopedDataStream',
                              'org.apache.camel.Attachment', 'org.apache.camel.attachment.Attachment',
                              'com.sap.it.script.v2.api.Exchange', 'com.sap.it.script.v2.api.language.SimpleBuilder']
                              .collectEntries { name -> [(name): safe { Class.forName(name) != null }] },
        // Which release of SAP's Script API bundle the runtime has (Maven Central: com.sap.cloud.script).
        scriptApiBundle    : safe { def c = Class.forName('com.sap.it.script.v2.api.Message'); c.protectionDomain?.codeSource?.location?.toString() },
        bindingVariables : safe { binding.variables.keySet().sort() },
    ]
    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
    return message
}
