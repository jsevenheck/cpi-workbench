package io.cpiwb.core;

import java.io.StringWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Node;

/**
 * Writes a DOM node as XML without a declaration. The JDK's identity transformer ends the lines it
 * writes itself (after a declaration, or between top-level nodes) with the computer's line separator,
 * CRLF on Windows, and ignores the line-separator output properties; the result here always uses LF,
 * so output does not depend on the computer. A carriage return in text or attribute values is written
 * as {@code &#13;} by the serializer, so replacing the separator touches only lines it wrote.
 */
public final class DomXml {

    private DomXml() {
    }

    public static String serialize(Node node, TransformerFactory factory) throws TransformerException {
        Transformer t = factory.newTransformer();
        // Explicitly XML: without a method the JDK's identity transformer writes a document whose root is an
        // unnamespaced <html> as HTML (<br> unclosed, a META element added), which is no longer XML.
        t.setOutputProperty(OutputKeys.METHOD, "xml");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(node), new StreamResult(w));
        return lf(w.toString());
    }

    public static String serialize(Node node) throws TransformerException {
        return serialize(node, TransformerFactory.newDefaultInstance());
    }

    static String lf(String s) {
        String sep = System.lineSeparator();
        return "\n".equals(sep) ? s : s.replace(sep, "\n");
    }
}
