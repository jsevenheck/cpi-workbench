package com.sap.gateway.ip.core.customdev.util;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamException;
import java.io.StringReader;

/**
 * A SOAP header of the message ({@code Message.getSoapHeaders()}). Constructors and accessors follow
 * SAP's published Script API ({@code com.sap.cloud.script:script.api}); the string constructor parses
 * the header element's XML.
 */
public class SoapHeader {

    private QName qname;
    private Element element;
    private boolean mustUnderstand;
    private String actor;

    public SoapHeader(QName qname, Element element, boolean mustUnderstand, String actor) {
        this.qname = qname;
        this.element = element;
        this.mustUnderstand = mustUnderstand;
        this.actor = actor;
    }

    public SoapHeader(QName qname, String xml, boolean mustUnderstand, String actor) throws XMLStreamException {
        this(qname, parse(xml), mustUnderstand, actor);
    }

    private static Element parse(String xml) throws XMLStreamException {
        if (xml == null) {
            return null;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (Exception e) {
            throw new XMLStreamException("SOAP header is not well-formed XML: " + e.getMessage(), e);
        }
    }

    public QName getQname() { return qname; }
    public void setQname(QName qname) { this.qname = qname; }
    public Element getElement() { return element; }
    public void setElement(Element element) { this.element = element; }
    public boolean isMustUnderstand() { return mustUnderstand; }
    public void setMustUnderstand(boolean mustUnderstand) { this.mustUnderstand = mustUnderstand; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    @Override
    public String toString() {
        return "SoapHeader[" + qname + "]";
    }
}
