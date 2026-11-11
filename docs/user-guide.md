# User guide

## Desktop app

Download the installer for your platform from the
[latest release](https://github.com/jsevenheck/cpi-workbench/releases/latest) (see
[Installing](install.md)), install it and open a project folder. Any folder works; **Init** adds `mappings/`, `schemas/`,
`scripts/`, `tests/` and example files in `platform/`.

* Open a `.xsl` or `.groovy` file in the tree and enter the input body, headers and properties on the left.
  Opening a Groovy script picks the runtime from its Script API import; for a pasted script, choose
  it under **Runtime**.
* **Format** above a mapping or script re-indents it. It only moves whitespace that cannot reach the
  result: text in XSLT, `xsl:text`, `xml:space="preserve"`, CDATA, literal attribute values and
  multi-line Groovy strings keep theirs.
* **Save** writes the open mapping, script or schema; **Save as…** writes it under a new name
  (`<name>-copy` is suggested) and leaves the open file as it is. Saving or creating a file under a
  name that exists asks before it overwrites the file, and opening another file asks before it
  drops unsaved changes (the dot after the file name).
* Right-click a file in the tree to open, rename, copy or delete it, or to copy its path.
* Drag the bars between input, mapping or script and output to resize them; double-click a bar to reset.
* **Save…** above the input body stores it under `inputs/`. Clicking a file there, or **Use as input**
  in a file's context menu, loads it as the input body of the current tool. A JSON file goes to the
  Groovy tab, unless Flow Steps is open (the Converter takes JSON); **Format** pretty-prints JSON too.
* Other files, such as `platform/*.json` or `cpi-workbench.json`, open in a **File** tab with their own
  editor. They no longer replace the input body.
* The output updates live, or press **Ctrl+Enter**. Compile and runtime errors are marked in the editor.
* Opening a file does not run its code. A Groovy script, a Groovy test case, and a stylesheet with
  reflexive `java:` calls run for the first time when you press **Run** or **Ctrl+Enter**; from then
  on live runs follow your edits. Such code runs with your rights, and a file may come from someone
  else (an imported iFlow, a shared project). Other stylesheets run when opened, as before.
* A message or file whose XML declaration names an encoding (`<?xml version="1.0" encoding="ISO-8859-1"?>`)
  is handled in that encoding: the body reaches the parser, or a script's `InputStream` / `byte[]`,
  in it, and the file is read and saved in it. A character the
  declared encoding lacks (`€` in ISO-8859-1) is an error. Text without such a declaration is UTF-8.
* A file cannot be renamed or copied to its own name in another spelling (`./a.xsl`, or `A.xsl` where
  the file system ignores case).
* **Parameters** (XSLT) shows how each `xsl:param` was bound: header, property or default.
* **MPL**, **Console** and **API calls** (Groovy) show message processing log entries, `println`
  output and the calls to SAP APIs with their arguments.
* Groovy scripts run like on a tenant: Java 21, Camel 3.14.7, timezone UTC and locale `en_US`
  whatever your computer uses, and the libraries a tenant offers (commons-lang3; on 1.x also Jackson
  and commons-codec; on 2.x yaml and toml). On 1.x, `log` and `logger` are SAP's `ScriptLogger`: `log.logInfo(…)`,
  `logWarnings(…)` and `logErrors(…)` print to the **Console**; `log.info(…)` fails, as on a tenant. Other jars go into the iFlow's `lib/`.
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  This helps before you move a script from 1.x to 2.x.
* Test cases (see [Test cases](test-cases.md)) are written by hand and run from the command line;
  the app has no test runner. Opening a `case.json` loads the case into its editor view. Inputs only
  a case file can hold (attachments, SOAP headers, platform overrides) are kept while the case is
  open and are shown below the input.
* **XSD** validates a document against a schema; **XPath** evaluates an expression (3.1 by default, or 1.0).
  Both run with **▶ Run** (Ctrl+Enter). XSD validation uses the JDK's validator, so it has no
  *Runtime* choice; for XPath the runtime picks the Saxon version of XPath 3.1 (1.0 runs on the JDK).
* **Flow Steps** runs a **Content Modifier**, **Filter**, **Router**, **General / Iterating Splitter**,
  **Gather** or **Converter** on an input message (body, headers, properties, namespace mapping):
  * Content Modifier: header and property rows with their source types and data types, and the
    message body; the result shows each row's value and the output message.
  * Router: routes in order, XML (XPath) or Non-XML (Camel Simple, with CPI's `=`, `and`, `or` and
    `${property.x}`) conditions, default route; the result shows which route the message takes.
  * Filter: an XPath and its Value Type (String, Integer, Boolean, Node, Nodelist); the result is the
    new body. `function:simple('${property.x}')` takes the value from Camel Simple.
  * Splitter: XPath, Line Break or Token, grouping; the result lists the messages. *Gather* passes them on.
  * Converter: JSON to XML, XML to JSON (namespace mapping, root element options, streaming), XML to CSV
    and CSV to XML (with an XSD from the project), with the settings of CPI's converter steps. *Use as
    input* chains them with the other steps, e.g. JSON to XML, then a Router.
  * Gather: Combine (SAP's multi-mapping format), Combine at XPath or Concatenate. The messages are on
    the left as *Body 1*, *Body 2*, …: from the splitter's *Gather* button, *From Splitter result*, or `+`
    for the current input body. Gather combines bodies only (SAP does not aggregate headers and properties),
    so the input pane shows no header or property tabs for it; the namespaces stay for *Combine at XPath*.

  Each step keeps its settings and its last result when you switch between the steps or tabs; the input
  message is shared by all steps but Gather. Nothing is kept after the app restarts.

  In XPath, `$name` is the header of that name, else the exchange property, as Camel's XPath variable
  resolver provides them ([Fidelity](fidelity.md), F39).

  Camel Simple and the Content Modifier run on Camel 3.14.7, as on a tenant for both script versions
  (tenants still on software 6.x / 5.x run Camel 2 and are not covered). XPath and the converters run on
  Saxon-HE 9.9. See [Fidelity](fidelity.md#flow-steps-content-modifier-filter-router-splitter-gather-converter).
* **Import iFlow** unpacks an iFlow exported from Integration Suite (zip) into `iflows/` and lists
  its steps. A Groovy Script or XSLT Mapping step opens its file with the step's function name and
  script version; a flow step opens in Flow Steps with its settings, with `{{parameters}}` from the
  iFlow's `parameters.prop`. Steps that choose their mapping from a header at runtime are marked. A
  **Message Mapping** step (**M**) is not simulated; clicking it opens the first Groovy script its
  `.mmap` uses, with its first mapping function. The `.iflw` format is not publicly documented, so
  it is read generically (best effort).
* **Completions** in Groovy scripts: after `message.` (or a variable declared as `Message`),
  `messageLogFactory.`, a message log (`MessageLog log`, or `def log = messageLogFactory.getMessageLog(...)`,
  also with `log?.`) and `ITApiFactory.`, the editor lists the methods of SAP's Script API with their
  parameters, for the script's API version (v1 or v2 imports). The editor has no type information, so
  other variable names get no suggestions. Ctrl+Space opens the list again.
* **Message Mapping functions** (custom functions such as `def String getHeader(String name,
  MappingContext context)` or `def void f(String[] values, Output output, MappingContext context)`):
  a script that has them lists them above the editor under **Run as**; a click runs one, or enter
  its name under **fn**. The label next to **fn** says *Script step* (the function gets the message)
  or *Mapping function*. A mapping function takes **Arguments** instead of the body: a text field per
  `String` (**null** passes null), and per `String[]` one context per line as a JSON array
  (`["A", "B"]`; `{"suppress": true}` and `{"xsiNil": true}` stand for SUPPRESS and xsi:nil). A
  `String[]` function runs once per context, a `String` function once (a tenant calls it once per
  value). A script opened from the file tree with only mapping functions runs the first one on its first run;
  each script keeps its function and arguments while the app is open, also when you switch to
  another script or tab and back. **Result** shows the returned
  value or the output queue; `MappingContext` reads and sets the Headers and Properties tabs. The
  mapping itself (`.mmap`) is not simulated. SAP does not support Groovy Script 2.x in Message
  Mappings, and a tenant ran mapping scripts on Groovy 2.4.21 and Java 21, so use 1.x
  ([Fidelity](fidelity.md)).
* **Lint** shows hints about what breaks when a script moves from 1.x to 2.x:
  * `XmlSlurper` or `XmlParser` used without a `groovy.xml` import,
  * Script API v1 imports or v1-only methods in a 2.x step,
  * wildcard imports.

**✕** closes the project in the engines too, so runs no longer see its files or `platform/` data.

### What a run can access

* XSLT, XSD and XPath runs read files inside the open project only: includes, `doc()`,
  `document()`, `unparsed-text()`, external entities and DTDs. The network is blocked. A symbolic
  link inside the project that leads outside it counts as outside; links within the project work.
  `xsl:result-document` and `collection()` are not supported. A run fails when it asks for a file
  outside the project, on both Saxon versions (Saxon 9.9 alone would go on with an empty `document()`). XPath runs (the XPath tab and flow
  steps) also refuse `fn:transform()`, and on Saxon 12 `fn:function-lookup()`: with a configuration of
  its own, a nested transformation would not be bound to the project. In XSLT, reflexive `java:` calls
  and `fn:transform()` are the exception: they are not restricted.
* XPath runs see no environment variables: `environment-variable()` returns nothing.
* Groovy scripts run as ordinary Java code with your user's rights; there is no sandbox. Run only
  scripts you trust.
* Camel Simple (Router conditions, Content Modifier expressions, `${...}` in other flow steps) can call
  Java methods on its values, so it is not restricted either. Flow steps run only when you press **Run step**.

The installers are not code-signed yet; [Installing](install.md) shows how to start them the
first time. The Windows `setup.exe` installs per user and needs no admin rights; the `.msi` installs
per machine and needs admin rights.

The Groovy runtime starts only when the Groovy or Flow Steps tab is used and stops after 10 idle minutes. Closing the app stops all engines; if the app ends without closing (a crash, Task Manager), the engines stop on their own as soon as it is gone.

**⚙** (bottom left, next to the status) shows the installed version. **Check for updates** asks github.com for the latest
release and links to its page if it is newer; download and install it from there.
If GitHub refuses, the menu shows its reason (without sign-in GitHub allows 60 requests per hour per
network address, which a shared company network can use up) and **Open releases page**. Apart from your
own scripts, this is the app's only network request, and it is made only when you click.

## Coding agents (MCP)

`cpi-workbench mcp` gives a coding agent such as Claude Code the app's tabs (XSLT, Groovy, XSD, XPath,
Flow Steps) over the Model Context Protocol. Setup, tools and what the server can access: [MCP server](mcp.md).

## Command line (CI-friendly)

```sh
java -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in input.xml -H SenderSystem=ERP
java -jar cpiwb-groovy-v2.jar test testdata/regression-project/tests
cpiwb-xml xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
cpiwb-xml validate --xsd schemas/order.xsd --in output.xml
cpiwb-xml xpath --expr "//Item/@sku" --in input.xml --version 3.1
cpiwb-xml test testdata/regression-project/tests        # exit code 1 if a case fails
```

`-H` sets a header and `-P` an exchange property, as `name=value[:Type]`. The types are listed in
[Test cases](test-cases.md). Each engine runs only the cases of its own kind and skips the others.

## Browser mode (no desktop shell needed)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode listens on localhost only and answers only requests that name this machine (`localhost`,
`127.0.0.1` or `[::1]` in the Host header, and a local origin), so a foreign web page cannot drive an
engine, not even one whose name is re-pointed to 127.0.0.1 (DNS rebinding). The engine that serves the
page also handles the project's files. After a timed-out call an engine starts again by itself, as in the desktop app (the command you start
only watches over a second process that serves); it then has no project open until the page's next call.
To add more engines, pass them in a URL parameter (engines on this machine only):

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```
