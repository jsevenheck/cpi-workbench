import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.ITApiFactory
import com.sap.it.api.keystore.KeystoreService
import com.sap.it.api.pd.PartnerDirectoryService
import com.sap.it.api.securestore.SecureStoreService
import groovy.json.JsonOutput

/*
 * Probe Q-P1: how do Partner Directory, Keystore, Secure Store and Number Range APIs behave for
 * entries that do not exist? Needs no Partner Directory content, no extra keystore entries and no
 * credentials: it only asks for names that cannot exist (the number range service is optional). Each call's result or exception (class + message) goes into the JSON response.
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
        attempt('pd.getCertificateParameter(missing)') { pd.getCertificateParameter(missing, missing) }
        attempt('pd.getAuthorizedUsers(missing)') { pd.getAuthorizedUsers(missing) }
        attempt('pd.getPartnerIdOfAuthorizedUser(missing)') { pd.getPartnerIdOfAuthorizedUser(missing) }
        attempt('pd.getParameterIdsMatchingPattern(bracket)') { pd.getParameterIdsMatchingPattern('[x]', missing, String) }
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
        attempt('ks.getKeyManagers(missing)') { ks.getKeyManagers(missing) }
        results['ks.methods'] = ks.getClass().getMethods().findAll { it.declaringClass != Object }
                .collect { it.toGenericString() }.sort()
    }

    def ss = null
    attempt('ss.service') { ss = ITApiFactory.getService(SecureStoreService, null) }
    if (ss != null) {
        attempt('ss.getUserCredential(missing)') { ss.getUserCredential(missing) }
        attempt('ss.getUserCredential(empty)') { ss.getUserCredential('') }
        attempt('ss.getAccesTokenForOauth2ClientCredential(missing)') { ss.getAccesTokenForOauth2ClientCredential(missing) }
    }

    // Looked up by name so the probe still compiles where the class is missing.
    def nr = null
    attempt('nrc.service') { nr = ITApiFactory.getService(Class.forName('com.sap.it.api.nrc.NumberRangeConfigurationService'), null) }
    if (nr != null) {
        attempt('nrc.getNextValuefromNumberRange(missing)') { nr.getNextValuefromNumberRange(missing, missing) }
    }
    attempt('factory.getService(ITApi)') { ITApiFactory.getService(Class.forName('com.sap.it.api.ITApi'), null) }

    message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson(results)))
    message.setHeader('Content-Type', 'application/json')
    return message
}
