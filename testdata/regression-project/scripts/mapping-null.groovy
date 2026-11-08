import com.sap.it.api.mapping.MappingContext

// A Message Mapping function that tells null from an empty string (regression: a test case's null argument).
def String classify(String value, MappingContext context) {
    return value == null ? 'NULL' : value.isEmpty() ? 'EMPTY' : 'VALUE'
}
