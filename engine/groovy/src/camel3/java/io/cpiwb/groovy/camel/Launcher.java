package io.cpiwb.groovy.camel;

import io.cpiwb.groovy.GroovyEngine;
import io.cpiwb.groovy.Main;

/** Entry point of the Groovy Script 2.x engine (Groovy 4, Camel 3, Script API v2). */
public final class Launcher {

    private Launcher() {
    }

    public static GroovyEngine engine() {
        return new GroovyEngine("v2", com.sap.it.script.v2.api.Message.class);
    }

    public static void main(String[] args) throws Exception {
        Main.run(args, engine());
    }
}
