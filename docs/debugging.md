# Debugging a Groovy script with breakpoints

The app has no debugger. A Groovy engine jar started with Java's debug agent (JDWP) lets a Java debugger stop in
your script: at a line, with the arguments (`message`) and local variables in view. This page shows how.

## What you need

* A full JDK 21 or newer (`java` and, for the check below, `jdb`). The runtime the app ships has no debug agent.
* The engine jar of the script's version: `cpiwb-groovy-v1.jar` (Groovy Script 1.x) or `cpiwb-groovy-v2.jar`
  (2.x), from a release or built with `mvn -f engine/pom.xml package -pl groovy-v1,groovy-v2 -am`.

## Start the script under the debug agent

```sh
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005 \
  -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in inputs/order.xml \
  -H SenderSystem=ERP --project .
```

`suspend=y` makes the JVM wait for the debugger before the script runs. The other options are those of `run`
(see the [user guide](user-guide.md#command-line-ci-friendly)); `test <path>` works the same way and stops in
every case that runs the script.

## Attach a debugger

* **jdb** (in every JDK):
  `jdb -connect com.sun.jdi.SocketAttach:hostname=127.0.0.1,port=5005`, then `stop at enrich:12`, `run`,
  and at the breakpoint `locals`, `where`, `next`, `cont`. On Windows `-attach 5005` tries shared memory and
  fails; use `-connect` as shown.
* **IntelliJ IDEA**: a *Remote JVM Debug* run configuration on `127.0.0.1:5005`, with the script's folder in the
  project; set the breakpoint in the script and start the configuration.
* **VS Code** (Java extension pack): a `launch.json` entry
  `{ "type": "java", "request": "attach", "name": "CPI script", "hostName": "127.0.0.1", "port": 5005 }`.

## How breakpoints find the script

The engine compiles the script into a class named after the file, without the extension; characters that are
not allowed in a Java name become `_`. `enrich.groovy` is the class `enrich`, `enrich-order.groovy` the class
`enrich_order` (with the source name `enrich_order.groovy`). A debugger that maps a breakpoint by the file's
name finds the script only when the file name is a Java name already, so name the script `enrichOrder.groovy`
or `enrich_order.groovy` while you debug it. In jdb, give the class name (`stop at enrich_order:12`).

What was checked (2026-11-16, SapMachine 25, Windows): with jdb, a line breakpoint in `processData` binds and
stops on both jars, and shows `message` and the local variables. IntelliJ IDEA and VS Code were not tried; that
their breakpoints bind follows from the class and source names above and is **not verified** (plan Q-D1).
