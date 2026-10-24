package io.cpiwb.xml;

import io.cpiwb.core.Json;

import java.util.Map;

/** One {@code xsl:message} emitted during a transformation. */
public final class XslMessage {

    public final String text;
    public final boolean terminate;
    public final int line;
    public final String systemId;

    public XslMessage(String text, boolean terminate, int line, String systemId) {
        this.text = text;
        this.terminate = terminate;
        this.line = line;
        this.systemId = systemId;
    }

    public Map<String, Object> toJson() {
        Json.Obj o = Json.o().with("text", text).with("terminate", terminate);
        if (line > 0) {
            o.with("line", line);
        }
        if (systemId != null) {
            o.with("systemId", systemId);
        }
        return o;
    }
}
