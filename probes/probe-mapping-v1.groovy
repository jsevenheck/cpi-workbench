import com.sap.it.api.mapping.MappingContext
import com.sap.it.api.mapping.Output
import groovy.json.JsonOutput

/*
 * Probe Q-M1: how does a Message Mapping call a script's custom functions? Assign this script to a
 * Message Mapping (Groovy Script 1.x: SAP's patch notes say version 2 is not supported in Message
 * Mappings) and map it as probes/README.md (step 6) describes:
 * - describeQueue (All values of a context): each call adds one JSON document with the runtime
 *   versions, the classes and methods behind MappingContext and Output (and Output's result list),
 *   and each input entry with its class, text and what Output.isContextChange / isSuppress / isXSINIL
 *   say. The target's Call elements show which documents land in which source context.
 * - describeValue (single values): returns the value and its class; one target Value per call.
 */
def void describeQueue(String[] values, Output output, MappingContext context) {
    def safe = { Closure c -> try { c() } catch (Throwable t) { 'unavailable: ' + t.getClass().getName() } }
    def methods = { Object o -> o == null ? null : o.getClass().getMethods().findAll { it.declaringClass != Object }
            .collect { it.toGenericString() }.sort() }
    def info = [
        probe        : 'probe-mapping-v1',
        java         : safe { java.lang.management.ManagementFactory.runtimeMXBean.specVersion }, // the runtime MXBean: the script editor refuses JVM property lookups
        javaVmVersion: safe { java.lang.management.ManagementFactory.runtimeMXBean.vmVersion },
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
        resultList   : safe {
            def list = output.getResultList()
            [
                'class'  : list?.getClass()?.getName(),
                methods  : methods(list),
                // public constants such as the context change and SUPPRESS markers: class and text
                constants: list == null ? null : list.getClass().getFields()
                        .findAll { (it.modifiers & 8) != 0 } // static
                        .collectEntries { f -> [(f.name): safe { def v = f.get(null); v == null ? null : v.getClass().getName() + ': ' + v }] },
            ]
        },
        unknownHeader: safe { context.getHeader('CPIWB_PROBE_DOES_NOT_EXIST') },
        headerCamelHttpMethod: safe { context.getHeader('CamelHttpMethod')?.toString() },
    ]
    output.addValue(JsonOutput.prettyPrint(JsonOutput.toJson(info)))
}

def String describeValue(String value, MappingContext context) {
    return JsonOutput.toJson([value: value, 'class': value == null ? null : value.getClass().getName()])
}
