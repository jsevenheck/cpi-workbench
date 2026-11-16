import com.sap.gateway.ip.core.customdev.util.Message

// Probe for Q-F1 (docs/plan.md): what Gather's Combine does with a body that is not XML.
// Runs after a General Splitter on /Order/Item. The header ProbeMode (an allowed header of the HTTPS
// sender) picks what the parts become:
//   mixed: only the second part (CamelSplitIndex 1) becomes JSON, the others stay XML;
//   json:  every part becomes JSON;
//   text:  every part becomes plain text without markup.
def Message processData(Message message) {
    def mode = (message.getHeaders().get('ProbeMode') ?: 'mixed').toString()
    def index = message.getProperties().get('CamelSplitIndex') as Integer
    def item = new XmlSlurper().parseText(message.getBody(String)).Item[0]
    def sku = item.@sku.toString()
    if (mode == 'json' || (mode == 'mixed' && index == 1)) {
        message.setBody('{"sku":"' + sku + '","qty":' + item.@qty + '}')
    } else if (mode == 'text') {
        message.setBody(sku + ' x ' + item.@qty)
    }
    return message
}
