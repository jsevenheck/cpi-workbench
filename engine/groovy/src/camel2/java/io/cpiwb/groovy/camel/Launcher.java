package io.cpiwb.groovy.camel;

import io.cpiwb.groovy.GroovyEngine;
import io.cpiwb.groovy.Main;

/** Entry point of the Groovy Script 1.x engine (Groovy 2.4, Camel 2, Script API v1). */
public final class Launcher {

    private Launcher() {
    }

    public static GroovyEngine engine() {
        return new GroovyEngine("v1", com.sap.gateway.ip.core.customdev.util.Message.class);
    }

    public static void main(String[] args) throws Exception {
        Main.run(args, engine());
    }
}
