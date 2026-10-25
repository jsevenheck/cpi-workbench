import com.sap.gateway.ip.core.customdev.util.Message

/*
 * Demo (Groovy Script 1.x): read a text attachment that came with the message (e.g. from a
 * mail or AS2 sender) and pass its first line on as a header.
 */
def Message processData(Message message) {
    def note = message.getAttachments()['delivery-note.txt']
    if (note == null) {
        throw new IllegalStateException('Attachment delivery-note.txt is missing')
    }
    def firstLine = note.getContent().text.readLines().find { it.trim() } ?: ''
    message.setHeader('DeliveryNote', firstLine.trim())
    message.setProperty('AttachmentCount', message.getAttachments().size())
    return message
}
