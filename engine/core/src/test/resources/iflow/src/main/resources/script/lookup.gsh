import com.sap.it.api.mapping.MappingContext

def String getHeader(String name, MappingContext context) {
    return context.getHeader(name)
}
