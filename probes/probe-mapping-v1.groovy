import com.sap.it.api.mapping.MappingContext
import com.sap.it.api.mapping.Output
import groovy.json.JsonOutput

/*
 * Probe Q-M1: how does a Message Mapping call a script's custom function? Assign this script to a
 * Message Mapping (Groovy Script 1.x: SAP's patch notes say version 2 is not supported in Message
 * Mappings) and map a repeating source field, with its context set to the root, through
 * describeQueue to one target field (see probes/README.md). The target field then holds a JSON
 * document: runtime versions, the classes and methods behind MappingContext and Output, and each
 * input entry with its class, text and what Output.isContextChange / isSuppress / isXSINIL say.
 */
def void describeQueue(String[] values, Output output, MappingContext context) {
    def safe = { Closure c -> try { c() } catch (Throwable t) { 'unavailable: ' + t.getClass().getName() } }
    def methods = { Object o -> o == null ? null : o.getClass().getMethods().findAll { it.declaringClass != Object }
            .collect { it.toGenericString() }.sort() }
    def info = [
        probe        : 'probe-mapping-v1',
        java         : System.getProperty('java.version'),
        groovy       : safe { GroovySystem.version },
        valuesClass  : values.getClass().getName(),
        entries      : values.collect { v -> [
            'class'        : v == null ? null : v.getClass().getName(),
            text           : v == null ? null : v.toString(),
            contextChange  : output.isContextChange(v),
            suppress       : output.isSuppress(v),
            xsiNil         : output.isXSINIL(v),
        ] },
        contextClass : context?.getClass()?.getName(),
        contextMethods: methods(context),
        outputClass  : output?.getClass()?.getName(),
        outputMethods: methods(output),
        unknownHeader: safe { context.getHeader('CPIWB_PROBE_DOES_NOT_EXIST') },
        headerCamelHttpMethod: safe { context.getHeader('CamelHttpMethod')?.toString() },
    ]
    output.addValue(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
}
