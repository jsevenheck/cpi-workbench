package io.cpiwb.groovy.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;

/** Camel 3.x glue (Script API v2 engine). */
public final class Exchanges {

    private static CamelContext context;

    private Exchanges() {
    }

    public static synchronized CamelContext context() {
        if (context == null) {
            DefaultCamelContext c = new DefaultCamelContext();
            c.disableJMX();
            c.start();
            context = c;
        }
        return context;
    }

    public static Exchange create() {
        return new DefaultExchange(context());
    }

    public static String camelVersion() {
        return context().getVersion();
    }
}
