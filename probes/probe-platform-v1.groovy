import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.pd.PartnerDirectoryService
import groovy.json.JsonOutput

/*
 * Probe Q-P1: how do Partner Directory and Keystore APIs behave for entries that do not exist?
 * Needs no Partner Directory content and no extra keystore entries: it only asks for names that
 * cannot exist. Each call's result or exception (class + message) goes into the JSON response.
 * Groovy Script 1.x step; the same file can run in a 2.x step after changing the Message import.
 */
def Message processData(Message message) {
    def missing = 'CPIWB_PROBE_DOES_NOT_EXIST_' + UUID.randomUUID().toString().take(8)
    def results = [:]
    def attempt = { String name, Closure c ->
        try {
            def r = c()
            results[name] = [result: r == null ? null : r.getClass().getName() + ': ' + r.toString().take(200)]
        } catch (Throwable t) {
            results[name] = [exception: t.getClass().getName(), message: String.valueOf(t.getMessage()).take(500)]
        }
    }

    def pd = null
    attempt('pd.service') { pd = ITApiFactory.getService(PartnerDirectoryService, null) }
    if (pd != null) {
        attempt('pd.getParameter(missing parameter, missing partner, String)') { pd.getParameter(missing, missing, String) }
        attempt('pd.getParameter(.., BinaryData)') { pd.getParameter(missing, missing, Class.forName('com.sap.it.api.pd.BinaryData')) }
        attempt('pd.getParameter(.., Integer)') { pd.getParameter(missing, missing, Integer) }
        attempt('pd.getPartnerId(missing)') { pd.getPartnerId(missing, missing, missing) }
        attempt('pd.getAlternativePartnerId(missing)') { pd.getAlternativePartnerId(missing, missing, missing) }
        attempt('pd.getParameter(empty id)') { pd.getParameter('', missing, String) }
        results['pd.methods'] = pd.getClass().getMethods().findAll { it.declaringClass != Object }
                .collect { it.toGenericString() }.sort()
    }

    def ks = null
    attempt('ks.service') { ks = ITApiFactory.getService(KeystoreService, null) }
    if (ks != null) {
        attempt('ks.getKey(missing)') { ks.getKey(missing) }
        attempt('ks.getCertificate(missing)') { ks.getCertificate(missing) }
        attempt('ks.getKeyPair(missing)') { ks.getKeyPair(missing) }
        attempt('ks.getCertificateChain(missing)') { ks.getCertificateChain(missing) }
        results['ks.methods'] = ks.getClass().getMethods().findAll { it.declaringClass != Object }
                .collect { it.toGenericString() }.sort()
    }

    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(results)))
    message.setHeader('Content-Type', 'application/json')
    return message
}
