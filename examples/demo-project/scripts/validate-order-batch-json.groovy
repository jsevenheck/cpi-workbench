import com.sap.it.script.v2.api.Message
import groovy.json.JsonSlurper

/*
 * Demo (Groovy Script 2.x): the JSON variant of validate-order-batch.groovy
 * (input: inputs/order-batch.json). Sets headers and exchange properties; the body stays as it is.
 */
def Message processData(Message message) {
    // Fine for small messages; read large ones from a Reader or stream instead of a String copy.
    String payload = message.getBody(String)
    def json = new JsonSlurper().parseText(payload)

    def batch = (json instanceof Map) ? json.salesOrderBatch : null
    if (!(batch instanceof Map)) {
        throw new IllegalArgumentException('Unexpected JSON: salesOrderBatch expected')
    }

    List orders = (batch.salesOrders instanceof List) ? batch.salesOrders : []
    int orderCount = orders.size()
    if (orderCount == 0) {
        throw new IllegalArgumentException('The batch contains no orders')
    }

    List<String> currencies = orders.collect { order ->
        order.totalAmount?.currency as String
    }.unique()
    if (currencies.size() != 1 || !currencies[0]) {
        throw new IllegalArgumentException('All orders must have the same currency')
    }

    // JsonSlurper returns decimals as BigDecimal and whole numbers as Integer or Long.
    BigDecimal batchTotal = orders.inject(BigDecimal.ZERO) { BigDecimal sum, order ->
        def amount = order.totalAmount?.value
        if (amount == null) {
            throw new IllegalArgumentException("Order ${order.orderId} has no totalAmount.value")
        }
        sum.add(new BigDecimal(amount.toString()))
    }
    String formattedTotal = batchTotal.setScale(2).toPlainString()
    String currency = currencies[0]

    // Headers, e.g. for a receiver adapter.
    message.setHeader('Content-Type', 'application/json; charset=UTF-8')
    message.setHeader('X-Order-Count', orderCount.toString())
    message.setHeader('X-Order-Total', formattedTotal)
    message.setHeader('X-Currency', currency)

    // Exchange properties, e.g. for a router, content modifier or monitoring.
    message.setProperty('orderCount', orderCount)
    message.setProperty('orderTotal', formattedTotal)
    message.setProperty('orderCurrency', currency)

    return message
}
