package com.sap.gateway.ip.core.customdev.util;

import io.cpiwb.groovy.MessageSupport;
import org.apache.camel.Exchange;

import javax.activation.DataHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Script API v1 message ({@code com.sap.gateway.ip.core.customdev.util.Message}), a clean-room
 * implementation of the methods of SAP's published interface ({@code com.sap.cloud.script:script.api}
 * 2.15.0). Not offered: the overloads that take Camel's own {@code Attachment} type
 * ({@code getAttachmentObjects()} and friends), because their Camel type changed between releases.
 */
public class Message extends MessageSupport<AttachmentWrapper> {

    private List<SoapHeader> soapHeaders = new ArrayList<>();

    public Message(Exchange exchange) {
        super(exchange);
    }

    @Override
    protected AttachmentWrapper attachment(DataHandler content) {
        return new AttachmentWrapper(content);
    }

    @Override
    protected DataHandler content(AttachmentWrapper attachment) {
        return attachment == null ? null : attachment.getDataHandler();
    }

    public void addAttachmentHeader(String name, String value, AttachmentWrapper attachment) {
        attachment.addHeader(name, value);
    }

    public void setAttachmentHeader(String name, String value, AttachmentWrapper attachment) {
        attachment.setHeader(name, value);
    }

    public String getAttachmentHeader(String name, AttachmentWrapper attachment) {
        return attachment.getHeader(name);
    }

    public void removeAttachmentHeader(String name, AttachmentWrapper attachment) {
        attachment.removeHeader(name);
    }

    public Map<String, AttachmentWrapper> getAttachmentWrapperObjects() {
        return attachmentMap();
    }

    public void setAttachmentWrapperObjects(Map<String, AttachmentWrapper> map) {
        attachmentMap().clear();
        if (map != null) {
            attachmentMap().putAll(map);
        }
    }

    public void addAttachmentObject(String id, AttachmentWrapper content) {
        attachmentMap().put(id, content);
    }

    /** SOAP headers received by a SOAP channel; set ones are sent by the receiver channel. */
    public List<SoapHeader> getSoapHeaders() {
        return soapHeaders;
    }

    public void setSoapHeaders(List<SoapHeader> headers) {
        soapHeaders = headers == null ? new ArrayList<>() : new ArrayList<>(headers);
    }

    public void clearSoapHeaders() {
        soapHeaders.clear();
    }
}
