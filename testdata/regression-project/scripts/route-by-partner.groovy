import com.sap.it.api.ITApiFactory
import com.sap.it.api.pd.PartnerDirectoryService
import com.sap.it.script.v2.api.Message

/*
 * Demo (Groovy Script 2.x): partner-dependent routing via the Partner Directory.
 * The sales channel from the header SalesChannel is mapped to a partner ID; its parameters give the
 * receiver URL and the sales organisation.
 */
def Message processData(Message message) {
    def pd = ITApiFactory.getService(PartnerDirectoryService, null)
    def channel = message.getHeader('SalesChannel', String)
    def partnerId = pd.getPartnerId('Shop', 'Channel', channel)
    if (partnerId == null) {
        throw new IllegalStateException("No partner maintained for sales channel ${channel}")
    }
    message.setProperty('PartnerId', partnerId)
    message.setProperty('ReceiverUrl', pd.getParameter('RECEIVER_URL', partnerId, String))
    message.setHeader('SalesOrg', pd.getParameter('SALES_ORG', partnerId, String))
    return message
}
