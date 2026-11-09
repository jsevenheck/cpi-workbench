package io.cpiwb.groovy.camel;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;

/** Camel 3.14 glue, shared by both engines (a tenant runs 1.x and 2.x on Camel 3.14.7, docs/plan.md F48). */
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
