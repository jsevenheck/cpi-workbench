package io.cpiwb.groovy.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.DefaultExchange;

/** Camel 2.x glue (Script API v1 engine). */
public final class Exchanges {

    private static CamelContext context;

    private Exchanges() {
    }

    public static synchronized CamelContext context() {
        if (context == null) {
            DefaultCamelContext c = new DefaultCamelContext();
            c.disableJMX();
            try {
                c.start();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot start Camel", e);
            }
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
