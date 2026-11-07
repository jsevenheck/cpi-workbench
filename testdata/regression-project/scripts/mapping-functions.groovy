import com.sap.it.api.mapping.MappingContext
import com.sap.it.api.mapping.Output

// Custom functions of a Message Mapping (run on their own; the mapping itself is not simulated).

def String getHeader(String headerName, MappingContext context) {
    return context.getHeader(headerName)
}

def String setProperty(String name, String value, MappingContext context) {
    context.setProperty(name, value)
    return value
}

// All values of a queue: the number of values in each context.
def void countPerContext(String[] values, Output output, MappingContext context) {
    int n = 0
    for (String v : values) {
        if (output.isContextChange(v)) {
            output.addValue(n)
            output.addContextChange()
            n = 0
        } else if (!output.isSuppress(v)) {
            n++
        }
    }
    output.addValue(n)
}
