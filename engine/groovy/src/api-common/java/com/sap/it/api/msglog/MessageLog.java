package com.sap.it.api.msglog;

import io.cpiwb.groovy.Platform;

import java.util.Date;

/** Clean-room simulation of the message processing log (MPL) entry of the current message. */
public class MessageLog {

    private final Platform platform;

    public MessageLog(Platform platform) {
        this.platform = platform;
    }

    public void setStringProperty(String name, String value) { platform.mplProperty("String", name, value); }
    public void setIntegerProperty(String name, Integer value) { platform.mplProperty("Integer", name, value); }
    public void setLongProperty(String name, Long value) { platform.mplProperty("Long", name, value); }
    public void setBooleanProperty(String name, Boolean value) { platform.mplProperty("Boolean", name, value); }
    public void setFloatProperty(String name, Float value) { platform.mplProperty("Float", name, value); }
    public void setDoubleProperty(String name, Double value) { platform.mplProperty("Double", name, value); }
    public void setDateProperty(String name, Date value) { platform.mplProperty("Date", name, value); }

    public void addCustomHeaderProperty(String name, String value) {
        platform.mplCustomHeader(name, value);
    }

    public void addAttachmentAsString(String name, String text, String mediaType) {
        platform.mplAttachment(name, text, mediaType);
    }
}
