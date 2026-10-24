import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.mapping.ValueMappingApi
import com.sap.it.api.securestore.SecureStoreService
import groovy.xml.XmlUtil

/*
 * Demo (Groovy Script 1.x): enrich a web shop order before the XSLT mapping.
 *  - value mapping: shop customer id -> S/4 sold-to party
 *  - secure store: technical user for a later HTTP call (only the user name is used here)
 *  - MPL: custom header property with the order number
 */
def Message processData(Message message) {
    def order = new XmlParser().parseText(message.getBody(String))
    def customerId = order.Customer[0].@id

    def vm = ITApiFactory.getService(ValueMappingApi, null)
    def soldTo = vm.getMappedValue('Shop', 'Customer', customerId, 'S4', 'SoldTo')
    if (soldTo == null) {
        throw new IllegalStateException("No sold-to party mapped for customer ${customerId}")
    }
    order.Customer[0].@soldTo = soldTo

    def credential = ITApiFactory.getService(SecureStoreService, null).getUserCredential('SHOP_API')
    message.setHeader('ShopUser', credential.getUsername())
    message.setProperty('SoldTo', soldTo)

    messageLogFactory.getMessageLog(message)?.addCustomHeaderProperty('OrderNumber', order.@number)

    message.setBody(XmlUtil.serialize(order))
    return message
}
