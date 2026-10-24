// CPI Workbench – runtime fingerprint for a Groovy Script step version 1.x (Script API v1).
// Put it into a test iFlow (e.g. HTTPS sender -> this script -> end) and send any request.
// The response body is a JSON document; paste it into an issue / the app's calibration import.
import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

def Message processData(Message message) {
    def safe = { Closure c -> try { c() } catch (Throwable t) { 'unavailable: ' + t.class.name } }
    def info = [
        probe              : 'fingerprint-v1',
        java               : System.getProperty('java.version'),
        javaVendor         : System.getProperty('java.vendor'),
        javaVm             : System.getProperty('java.vm.name'),
        groovy             : safe { GroovySystem.version },
        camel              : safe { Class.forName('org.apache.camel.CamelContext').package?.implementationVersion
                                        ?: Class.forName('org.apache.camel.impl.DefaultCamelContext').getDeclaredConstructor().newInstance().version },
        camelPom           : safe { ['camel-core', 'camel-api', 'camel-core-engine'].collect { a ->
                                        def p = new Properties()
                                        def stream = Class.forName('org.apache.camel.CamelContext').getResourceAsStream("/META-INF/maven/org.apache.camel/${a}/pom.properties")
                                        if (stream) { p.load(stream); "${a} ${p.version}" } else null }.findAll() },
        saxon              : safe { Class.forName('net.sf.saxon.Version').getMethod('getProductTitle').invoke(null) },
        defaultCharset     : java.nio.charset.Charset.defaultCharset().name(),
        timezone           : TimeZone.default.ID,
        locale             : Locale.default.toString(),
        transformerFactory : safe { javax.xml.transform.TransformerFactory.newInstance().class.name },
        xpathFactory       : safe { javax.xml.xpath.XPathFactory.newInstance().class.name },
        schemaFactory      : safe { javax.xml.validation.SchemaFactory.newInstance('http://www.w3.org/2001/XMLSchema').class.name },
        documentBuilder    : safe { javax.xml.parsers.DocumentBuilderFactory.newInstance().class.name },
        messageClass       : message.class.name,
        bodyClass          : safe { message.getBody()?.class?.name },
        // Which optional Groovy modules / libraries the runtime offers (the simulator bundles
        // groovy-xml, -json, -dateutil, -datetime, -templates, -nio for 2.x).
        classesAvailable   : ['groovy.util.XmlSlurper', 'groovy.xml.XmlSlurper', 'groovy.json.JsonSlurper',
                              'groovy.text.SimpleTemplateEngine', 'groovy.sql.Sql', 'groovy.yaml.YamlSlurper',
                              'groovy.toml.TomlSlurper', 'groovy.ant.AntBuilder', 'org.apache.commons.lang3.StringUtils',
                              'com.fasterxml.jackson.databind.ObjectMapper', 'org.apache.poi.ss.usermodel.Workbook']
                              .collectEntries { name -> [(name): safe { Class.forName(name) != null }] },
        bindingVariables   : safe { binding.variables.keySet().sort() },
        headerTypes        : safe { message.headers.collectEntries { k, v -> [(k): v?.class?.name] } },
        propertyTypes      : safe { message.properties.collectEntries { k, v -> [(k): v?.class?.name] } },
    ]
    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
    message.setHeader('Content-Type', 'application/json')
    return message
}
