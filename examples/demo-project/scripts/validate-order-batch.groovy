import com.sap.it.script.v2.api.Message
import groovy.xml.XmlSlurper

/*
 * Demo (Groovy Script 2.x): validates the SalesOrderBatch from mappings/orders-to-batch.xsl
 * (input: inputs/order-batch.xml) and sets headers and exchange properties. The body stays as it is.
 */
def Message processData(Message message) {
    final String targetNamespace = 'urn:demo:integration:orders:target:v1'

    // Fine for small messages; read large ones from a Reader or stream instead of a String copy.
    String payload = message.getBody(String)
    def root = new XmlSlurper(false, true).parseText(payload)
    root.declareNamespace(t: targetNamespace)

    if (root.name() != 'SalesOrderBatch' || root.namespaceURI() != targetNamespace) {
        throw new IllegalArgumentException('Unexpected XML: SalesOrderBatch expected')
    }

    def orders = root.'t:SalesOrders'.'t:SalesOrder'
    int orderCount = orders.size()
    if (orderCount == 0) {
        throw new IllegalArgumentException('The batch contains no orders')
    }

    List<String> currencies = orders.collect { order ->
        order.'t:TotalAmount'.@currency.text()
    }.unique()
    if (currencies.size() != 1 || !currencies[0]) {
        throw new IllegalArgumentException('All orders must have the same currency')
    }

    BigDecimal batchTotal = orders.inject(BigDecimal.ZERO) { BigDecimal sum, order ->
        String amount = order.'t:TotalAmount'.text()
        sum.add(new BigDecimal(amount))
    }
    String formattedTotal = batchTotal.setScale(2).toPlainString()
    String currency = currencies[0]

    // Headers, e.g. for a receiver adapter.
    message.setHeader('Content-Type', 'application/xml; charset=UTF-8')
    message.setHeader('X-Order-Count', orderCount.toString())
    message.setHeader('X-Order-Total', formattedTotal)
    message.setHeader('X-Currency', currency)

    // Exchange properties, e.g. for a router, content modifier or monitoring.
    message.setProperty('orderCount', orderCount)
    message.setProperty('orderTotal', formattedTotal)
    message.setProperty('orderCurrency', currency)

    return message
}
