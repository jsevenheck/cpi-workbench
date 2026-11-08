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
* **Parameters** (XSLT) shows how each `xsl:param` was bound: header, property or default.
* **MPL**, **Console** and **API calls** (Groovy) show message processing log entries, `println`
  output and the calls to SAP APIs with their arguments.
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  This helps before you move a script from 1.x to 2.x.
* Test cases (see [Test cases](test-cases.md)) are written by hand and run from the command line;
  the app has no test runner. Opening a `case.json` loads the case into its editor view. Inputs only
  a case file can hold (attachments, SOAP headers, platform overrides) are kept while the case is
  open and are shown below the input.
* **XSD** validates a document against a schema; **XPath** evaluates an expression (3.1 by default, or 1.0).
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

  These steps have no Groovy version: *Camel* in the top bar picks the tenant's Camel line (3.14
  for tenant software 7.x / 8.x, the default; 2.24 for 5.x / 6.x). XPath and the converters run on
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
  `String[]` function runs once per context, a `String` function once. **Result** shows the returned
  value or the output queue; `MappingContext` reads and sets the Headers and Properties tabs. The
  mapping itself (`.mmap`) is not simulated. SAP does not support Groovy Script 2.x in Message
  Mappings, so use 1.x ([Fidelity](fidelity.md)).
* **Lint** shows hints about what breaks when a script moves from 1.x to 2.x:
  * `XmlSlurper` or `XmlParser` used without a `groovy.xml` import,
  * Script API v1 imports or v1-only methods in a 2.x step,
  * wildcard imports.

**✕** closes the project in the engines too, so runs no longer see its files or `platform/` data.

### What a run can access

* XSLT, XSD and XPath runs read files inside the open project only: includes, `doc()`,
  `document()`, `unparsed-text()`, external entities and DTDs. The network is blocked. A symbolic
  link inside the project that leads outside it counts as outside; links within the project work.
  `xsl:result-document` and `collection()` are not supported. XPath runs (the XPath tab and flow
  steps) also refuse `fn:transform()`, and on Saxon 12 `fn:function-lookup()`: with a configuration of
  its own, a nested transformation would not be bound to the project. In XSLT, reflexive `java:` calls
  and `fn:transform()` are the exception: they are not restricted.
* Groovy scripts run as ordinary Java code with your user's rights; there is no sandbox. Run only
  scripts you trust.

The installers are not code-signed yet; [Installing](install.md) shows how to start them the
first time. The Windows `setup.exe` installs per user and needs no admin rights; the `.msi` installs
per machine and needs admin rights.

The Groovy runtime starts only when the Groovy or Flow Steps tab is used and stops after 10 idle minutes.

**⚙** (top right) shows the installed version. **Check for updates** asks github.com for the latest
release and links to its page if it is newer; download and install it from there. Apart from your
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

Web mode listens on localhost only. The engine that serves the page also handles the project's
files. To add more engines, pass them in a URL parameter:

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```
