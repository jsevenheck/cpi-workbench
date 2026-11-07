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

// All values of a context (String[] arguments, Output): called once per context.
def void countPerContext(String[] values, Output output, MappingContext context) {
    output.addValue(values.count { !output.isSuppress(it) })
}
