// Runs before probe-xslt-behaviour.xsl. Sets headers and properties with names and Java types the
// probe stylesheet asks about. Script API v1 (works in a Groovy Script 1.x step).
import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    message.setHeader('ProbeShared', 'fromHeader')
    message.setProperty('ProbeShared', 'fromProperty')
    message.setHeader('ProbeHeaderOnly', 'h')
    message.setProperty('ProbePropertyOnly', 'p')
    message.setHeader('ProbeInteger', Integer.valueOf(41))
    message.setHeader('ProbeBoolean', Boolean.TRUE)
    message.setHeader('ProbeDate', new Date(0))
    message.setHeader('ProbeBytes', 'AB'.getBytes('UTF-8'))
    message.setBody('<Probe><Item>1</Item><Item>2</Item></Probe>')
    return message
}
