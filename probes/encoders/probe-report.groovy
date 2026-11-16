import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

// Probe for Q-E1 (docs/plan.md): what an Encoder, Decoder or Message Digest step leaves in the message.
// Runs after the step under test and answers with JSON: the body's class and its bytes as Base64, so that
// line ends and binary results reach the response file unchanged, and the headers the step may have set.
def Message processData(Message message) {
    def body = message.getBody()
    byte[] bytes = message.getBody(byte[].class) ?: new byte[0]
    def headers = message.getHeaders()
    // Values only for headers such a step may set: a tenant's other headers can hold host names and ids.
    def shown = headers.findAll { name, value -> name ==~ /(?i).*(digest|filename|zip|encoding|content-type).*/ }
    def report = [
        probeStep  : String.valueOf(headers.get('ProbeStep')),
        bodyClass  : body == null ? null : body.getClass().getName(),
        bodyLength : bytes.length,
        bodyBase64 : bytes.encodeBase64().toString(),
        bodyText   : text(bytes),
        headerNames: headers.keySet().collect { String.valueOf(it) }.sort(),
        headers    : shown.collectEntries { name, value ->
            [name, [value: String.valueOf(value), type: value == null ? null : value.getClass().getName()]]
        },
    ]
    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(report)))
    message.setHeader('Content-Type', 'application/json')
    return message
}

// The body as text when its bytes are UTF-8, for reading the report; null for a binary body.
def String text(byte[] bytes) {
    try {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (CharacterCodingException e) {
        return null
    }
}
