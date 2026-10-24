// CPI Workbench – runtime fingerprint for a Groovy Script step version 2.x (Script API v2).
// NOTE: written from public community information (package com.sap.it.script.v2.api); not yet
// verified on a tenant. If it does not compile, the error message itself is useful – please send it.
import com.sap.it.script.v2.api.Message
import groovy.json.JsonOutput

def Message processData(Message message) {
    def safe = { Closure c -> try { c() } catch (Throwable t) { 'unavailable: ' + t.class.name } }
    def info = [
        probe          : 'fingerprint-v2',
        java           : System.getProperty('java.version'),
        javaVendor     : System.getProperty('java.vendor'),
        groovy         : safe { GroovySystem.version },
        camel          : safe { Class.forName('org.apache.camel.CamelContext').package?.implementationVersion ?: 'unknown' },
        saxon          : safe { Class.forName('net.sf.saxon.Version').getMethod('getProductTitle').invoke(null) },
        defaultCharset : java.nio.charset.Charset.defaultCharset().name(),
        timezone       : TimeZone.default.ID,
        locale         : Locale.default.toString(),
        messageClass   : message.class.name,
        // The v2 API surface is still unknown (PLAN.md F6): list it by reflection.
        messageMethods : safe { message.class.methods.findAll { it.declaringClass.name.startsWith('com.sap') }
                                    .collect { it.name + '(' + it.parameterTypes*.simpleName.join(', ') + '): ' + it.returnType.simpleName }
                                    .unique().sort() },
        interfaces     : safe { message.class.interfaces*.name },
        bindingVariables : safe { binding.variables.keySet().sort() },
    ]
    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
    return message
}
