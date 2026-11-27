# User guide

## Desktop app

Download the installer for your platform from the
[latest release](https://github.com/jsevenheck/cpi-workbench/releases/latest) (see
[Installing](install.md)), install it and open a project folder, or import an exported iFlow into a new one. Any folder works; **Init** adds `mappings/`, `schemas/`,
`scripts/` and example files in `platform/` ([Project files](project-files.md)).

Without a project, **Examples…** in the sidebar loads a mapping or script of the demo project with its input
into the editors (an XSLT 3.0 mapping, a Groovy 2.x script for an XML and one for a JSON body). The examples
come with the app; **Save** asks where to keep them, once a project is open. As with a file, a script waits for
**Run** or **Ctrl+Enter**.

### Files and editors

* **Fidelity** (next to **Runtime**, and next to **Run step** in Flow Steps) says how well the open step's
  behaviour is backed: *Tenant-verified*, *Documented* (SAP's documentation or Camel's / Saxon's behaviour, not seen
  on a tenant) or *Assumptions*, the weakest of its rows on the [Fidelity](fidelity.md) page. A click lists the
  rows, also what the simulator refuses. The page is part of the app; nothing is fetched.
* Open a `.xsl` or `.groovy` file in the tree and enter the input body, headers and properties on the left.
  Opening a Groovy script picks the runtime from its Script API import; for a pasted script, choose
  it under **Runtime**.
* **Format** above a mapping or script re-indents it. It only moves whitespace that cannot reach the
  result: text in XSLT, `xsl:text`, `xml:space="preserve"`, CDATA, literal attribute values and
  multi-line Groovy strings keep theirs.
* **Save** writes the open mapping, script or schema; **Save as…** writes it under a new name
  (`<name>-copy` is suggested) and leaves the original on disk. When you choose a destination to save,
  create, copy or rename a file, the filesystem checks whether it exists, even if the file is not in
  the tree. An existing destination is replaced only after you choose **Overwrite**; **Save as…** onto
  the open file's own path saves it without asking. Opening another
  file asks before dropping unsaved changes (the dot after the file name). You can keep typing during
  a save; edits made after the write started keep the dot until you save them too.
* The editors are Monaco's: right-click in an editor for its context menu, **F1** for its command palette,
  **Ctrl+G** to go to a line; sticky scroll keeps the enclosing elements or blocks at the top while scrolling.
* Right-click a file in the tree to open, rename, copy or delete it, or to copy its path.
* The **×** next to a file name closes that file; the file on disk stays as it is. It asks first when there
  are unsaved changes. In the mapping, script and schema panes it leaves an empty editor without a path, so
  **Save** asks for a new name. Next to
  the input file it empties the input body; headers and properties stay. The **File** tab closes the same way, also from the × on its
  tab in the tool bar.
* Drag the bars between input, mapping or script and output to resize them; double-click a bar to reset.
* **Save…** above the input body stores it under `inputs/`. Clicking a file there, or **Use as input**
  in a file's context menu, loads it as the input body of the current tool. A JSON file goes to the
  Groovy tab, unless Flow Steps is open (the Converter takes JSON); **Format** pretty-prints JSON too.
* Other files, such as `platform/*.json` or `cpi-workbench.json`, open in a **File** tab with their own
  editor rather than replacing the input body.
* The output updates live, or press **Ctrl+Enter**. Compile and runtime errors are marked in the editor.
* A newly opened Groovy script waits for **Run** or **Ctrl+Enter**; afterwards Live follows your edits.
  XSLT Live previews never enable reflexive `java:` calls, including in included or imported modules.
  Use **Run** or **Ctrl+Enter** each time a mapping needs Java. Such code runs with your rights, so trust
  the source of imported iFlows and shared projects. Other stylesheets can preview when opened.
  Code imported from a message link always waits for a manual first run.
* A message or file whose XML declaration names an encoding (`<?xml version="1.0" encoding="ISO-8859-1"?>`)
  is handled in that encoding: the body reaches the parser, or a script's `InputStream` / `byte[]`,
  in it, and the file is read and saved in it. A character the
  declared encoding lacks (`€` in ISO-8859-1) is an error, in a run and when saving. Text without such a declaration is UTF-8.
* A file cannot be renamed or copied to its own name in another spelling (`./a.xsl`, or `A.xsl` where
  the file system ignores case).
### Groovy, XSLT and platform data

* **Parameters** (XSLT) shows how each `xsl:param` was bound: header, property or default.
* **Body type** (Groovy, `as` above the input body) picks what `message.getBody()` returns without a class:
  `String` (default), `byte[]` or `InputStream`. `InputStream` gives Camel's `InputStreamCache` and is reset
  after the step so a script that only reads the body passes it on whole ([Fidelity](fidelity.md#groovy-script));
  if a script run on a `String` body fails calling a method with that `String`, a diagnostic suggests `InputStream`.
* **MPL**, **Console** and **API calls** (Groovy) show message processing log entries, `println`
  output and the calls to SAP APIs with their arguments.
* **Attachments** and **SOAP** (Groovy, next to Headers and Properties) hold what the script reads with
  `message.getAttachments()` and `getSoapHeaders()` (both simulator profiles; SOAP is documented in v1
  and observed on the v2 runtime): an attachment's content is text typed
  there, a file of the project (its path) or Base64. A Message Mapping function gets no message, so the two tabs are hidden for it.
* **Platform** (Groovy) shows the mocked platform data the script reads through `ITApiFactory` as tables:
  value mappings, the Secure Store's credentials, Partner Directory parameters and number ranges, from
  the project's `platform/*.json` files ([format](project-files.md#mocked-platform-data-platform)). A changed
  table applies to the next run at once and is marked with a dot until **Save** writes its file, as
  indented JSON (numbers keep their spelling). What a table does not show stays in the file as it is: a credential's properties and
  OAuth2 tokens, binary parameters, alternative partners and authorized users; **Open file** edits
  those. The keystore is edited as a file. Without a project the tables work in memory. Use test-only values, never a
  tenant's real secrets.
* The Groovy profiles follow one tenant's fingerprint: Java 21, Camel 3.14.7, timezone UTC and
  locale `en_US` whatever your computer uses, with commons-lang3; on 1.x also Jackson and
  commons-codec; on 2.x yaml and toml. On 1.x, `log` and `logger` imitate the observed `ScriptLogger`
  methods: `log.logInfo(…)`, `logWarnings(…)` and `logErrors(…)` print to the **Console** with a note
  because CPI's output destination is unknown; `log.info(…)` fails as on the probed tenant.
  Extra jars go into the project's `lib/` or an imported iFlow's `src/main/resources/lib/`.
  Versions, exclusions and unverified behaviour: [Fidelity](fidelity.md#groovy-script).
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  This helps before you move a script from 1.x to 2.x.
* **History** (XSLT and Groovy output) lists the tab's last 20 runs of this session: time, file (and a
  script's function), runtime and status. *Compare with the previous run*, or *Compare* on any run, shows
  what differs from the result shown now, as **Compare** does. A run with the same outcome as the one
  before only updates its time, so live runs while you type do not crowd the list. The history is kept in
  memory and is gone when the app closes.
### XSD and XPath

* **XSD** validates a document against a schema; **XPath** evaluates an expression (3.1 by default, or 1.0).
  Both run with **▶ Run** (Ctrl+Enter). XSD validation uses the JDK's validator, so it has no
  *Runtime* choice; for XPath the runtime picks the Saxon version of XPath 3.1 (1.0 runs on the JDK).
* In the **XPath** tab, the nodes an expression selects are marked in the document, and a result with ↗
  shows its place. Edit the document and the marks go until the next run. **XPath at cursor** writes the
  XPath of the element or attribute at the cursor, for that node (with positions where siblings share the
  name) or for all like it; the choice goes into the expression field and the clipboard, and runs. Its
  prefixes come from the **Namespace mapping**; a namespace without a prefix there is written `Q{uri}name`
  (3.1) or with `local-name()` (1.0), and the status line says which to add. **↑** and **↓** in the field
  bring back the expressions run in this session. A document with entity references (`&name;` other than
  `&amp;`, `&lt;` and the like) in its content or in a namespace declaration can hide elements or the namespace
  from the text, so the tab neither marks nor writes paths for it.
### Flow Steps

* **Flow Steps** runs a **Content Modifier**, **Filter**, **Router**, **General / Iterating Splitter**,
  **Gather**, **Converter**, **Encoder**, **Decoder** or **Message Digest** on an input message (body, headers,
  properties, namespace mapping). With only a Saxon engine, XML-only steps such as the Encoder still
  run; the tab names the missing runtime if the current settings also require Camel:
  * Content Modifier: header and property rows with their source types and data types, and the
    message body; the result shows each row's value and the output message. Leave *Message Body* empty
    to keep the incoming body, including binary bytes. *Use as input* passes them on with the updated
    headers and properties; *Save…* writes the bytes to a file.
  * Router: routes in order, XML (XPath) or Non-XML (Camel Simple, with CPI's `=`, `and`, `or` and
    `${property.x}`) conditions, default route; the result shows which route the message takes.
  * Filter: an XPath and its Value Type (String, Integer, Boolean, Node, Nodelist); the result is the
    new body. `function:simple('${property.x}')` takes the value from Camel Simple; inside the XPath's own
    string literals and `(: :)` comments it is plain text.
  * Splitter: XPath, Line Break or Token, grouping; the result lists the messages. *Gather* passes them on.
  * Converter: JSON to XML, XML to JSON (namespace mapping, root element options, streaming), XML to CSV
    and CSV to XML (with an XSD from the project), with the settings of CPI's converter steps. *Use as
    input* chains them with the other steps, e.g. JSON to XML, then a Router.
  * Encoder and Decoder: *Base64 Encode* / *Decode*, *GZIP Compress* / *Decompress* and *ZIP Compress* /
    *Decompress*, as Camel's data formats do them. That CPI does the same is not verified on a tenant, and the
    result says so: Base64 Encode writes lines of 76 characters with CR LF, ZIP Compress names its one entry
    after the header `CamelFileName` (set it in Headers), ZIP Decompress takes an archive with one entry.
    A result that is no text shows its size and a hex dump of its first bytes; *Use as input* passes the
    bytes on (to a Decoder, for instance) and *Save…* writes them to a file. A `.zip` or `.gz` file of the
    project opens as a binary input body in the same way (click it in the tree, in any folder, or *Use as input*).
    *MIME Multipart* is not simulated.
  * Message Digest: the message, or the one element a *Filter (XPath)* selects, is written as canonical
    XML (the six methods CPI offers, or *None*), and its digest (MD5, SHA1, SHA256, SHA384, SHA512) goes
    to the *Target Header Name* as Base64 text; the body stays. The result shows the header and the
    canonical form the digest is of, which is what to look at when two messages you took for equal have
    different digests. The filter is XPath 1.0 with the prefixes of the Namespaces tab. Not known from
    SAP's documentation, and therefore refused or marked as an assumption: a filter that selects several
    nodes or none, a filter together with *None*, and what *None* hashes (here: the body's bytes as they are).
  * Gather: Combine (SAP's multi-mapping format), Combine at XPath or Concatenate. The messages are on
    the left as *Body 1*, *Body 2*, …: from the splitter's *Gather* button, *From Splitter result*, or `+`
    for the current input body. Gather combines bodies only (SAP does not aggregate headers and properties),
    so the input pane shows no header or property tabs for it; the namespaces stay for *Combine at XPath*.

  Each step keeps its settings and its last result when you switch between the steps or tabs; the input
  message is shared by all steps but Gather. Nothing is kept after the app restarts. The steps start empty
  (no rows, routes or expressions; SAP's defaults where a step has a choice), as do the input bodies, the
  mapping and the script editors: without a project, **Examples…** loads something to try.

  In XPath, `$name` is the header of that name, else the exchange property, as Camel's XPath variable
  resolver provides them ([Fidelity](fidelity.md), F39).

  Camel Simple and the Content Modifier run on Camel 3.14.7, as on a tenant for both script versions
  (the documented software 6.x / 5.x lines use Camel 2 and are not covered; their current tenant
  availability is unknown). XPath, the converters, the Encoder / Decoder and Message Digest run on
  an installed XML engine (Saxon-HE 9.9 by default, otherwise 12). See [Fidelity](fidelity.md#flow-steps-content-modifier-filter-router-splitter-gather-converter).
### iFlow import

* **Without a project**, **Import iFlow…** on the start screen starts one from an export: it asks for the zip, then
  for a new project folder (named after the zip by default). The app creates that folder with the standard layout
  (as **Init** does) and imports into it. A folder that exists already is refused, so an existing one is never
  initialized by mistake. If the import fails, the new project stays open for another try.
* **Import iFlow** unpacks an iFlow exported from Integration Suite (zip) into `iflows/` of the open project and
  lists its steps. A folder that differs from one already there only in letter case or trailing dots is
  refused, since Windows takes both for one folder. A Groovy Script or XSLT Mapping step opens its file with the step's function name and
  script version; a flow step opens in Flow Steps with its settings, with `{{parameters}}` from the
  iFlow's `parameters.prop`; the line *From <iFlow> · <step>* above its settings says where they came from, and
  a choice the iFlow leaves out (an Encoder's scheme, a Message Digest's algorithm, …) takes the tab's default,
  which a line in warning colour names as an assumption; its **×** gives the step its empty starting settings back (the iFlow stays as it is; closing the project does the
  same for all its steps). Steps that choose their mapping from a header at runtime are marked. A
  **Message Mapping** step (**M**) is not simulated; clicking it opens the first Groovy script its
  `.mmap` uses, with its first mapping function. The `.iflw` format is not publicly documented, so
  it is read generically (best effort).
* The same button takes an exported **Value Mapping**: its groups become rows of
  `platform/value-mappings.json` in both directions (*Platform* → *Value Mappings*), replacing rows of
  the same agencies and identifiers. A value that maps to several values gets the one marked default;
  without a default the first group in the file is taken, and the status line says so.
* It also takes a whole **integration package** export: each iFlow, script collection, REST API provider
  and message mapping goes to `iflows/<its id>/`, its value mappings into the table, other artifact types
  are named as skipped. A Groovy step whose script is in a script collection opens the script from that
  collection's folder. If two artifacts would use the same folder after replacing unsupported characters
  with `_` (also ignoring letter case and trailing dots), the entire package is rejected before any files or value mappings
  are written, even when overwriting is allowed. Resolve the conflicting artifact identifiers before
  exporting again, or export the artifacts separately and import them under distinct folder names.
* The list under **iFlows** shows each imported iFlow whole: its adapters (⇢ from a sender, ⇠ to a
  receiver), then each process (main, exception subprocesses, local processes) with its elements in flow
  order. A Router's routes are indented under it with their conditions, the default marked; where routes
  meet, the step is listed again with ↪; elements no flow reaches come last. A step a tab can run opens
  there, as before; any other element opens in the **Diagram** tab with its settings (read-only).
* iFlows that share a name (a copied iFlow keeps its name) are told apart by their folder, here, in the
  Diagram's iFlow picker and in Where used: *DoxisRESTLoginFlow · CopyDoxisObject*.
* **Diagram** (shown once iFlows are imported) draws an iFlow in the layout its export stores, without
  SAP's icons: pools, steps, Routers, events, adapters and exception subprocesses. Scroll to zoom, drag to
  move, **Fit** shows all of it. A click shows an element's type and settings (for a sender or receiver,
  also its adapters' settings); a step that runs in the
  app (blue outline) opens with a double-click or **Open in …**. Nothing in the diagram can be changed.
  The ⌕ next to a setting searches for its value in **Where used**.
* **Where used** (next to Diagram) works offline over all imported iFlows.
  * **Overview** (empty search): the addresses, credentials, queues, key aliases and files the iFlows' settings
    hold, each with the number of places that use it; a click searches for that value. Files are folded and listed
    by name (the path is the tooltip). Below, **Parameters** lists each iFlow's externalized parameters with value,
    type and description (`parameters.prop`, `parameters.propdef`), where they are used, and marks those that are
    *unused* or have *no value*. **← Overview** leaves a search; the iFlow picker narrows both views.
  * **Search**: type a host, a credential or key alias, a queue, a script file, a `{{parameter}}` or any value; it
    lists every step, adapter and Router route whose settings contain it, grouped by iFlow. A key name (`address`)
    finds that setting everywhere. **Kind** narrows to addresses, credentials, queues, key aliases, files or
    parameters, where the adapter's setting says what it holds (from public exports; other settings are searched
    as plain values). A receiver adapter's hit names the step that calls it (*SuccessFactors receiver from Get
    Employee*). A hit in a Content Modifier's header or property table shows only the rows that contain the search,
    as their cells (*Action*, *Type*, *Name*, *Value*). A click shows the element in the diagram; a script or mapping
    opens in its tab. Above the hits,
    **Parameters** lists the parameters whose name or value contains the search (`dx4Auth` finds the parameter
    whose value it is), with their uses.
  * Settings are searched as the export writes them: a setting that holds `{{SftpHost}}` is found by `SftpHost`,
    not by the host. Next to such a value, **→** shows what `parameters.prop` resolves it to (in the overview,
    *→ 2 values* when iFlows resolve it differently). A parameter used in a Content Modifier table counts as the
    same parameter, also when its name has a quote.

### Groovy completions and Message Mapping functions

* **Completions** in Groovy scripts: after `message.` (or a variable declared as `Message`),
  `messageLogFactory.`, a message log (`MessageLog log`, or `def log = messageLogFactory.getMessageLog(...)`,
  also with `log?.`) and `ITApiFactory.`, the editor lists the methods of SAP's Script API with their
  parameters, for the script's API version (v1 or v2 imports). The editor has no type information, so
  other variable names get no suggestions. Ctrl+Space opens the list again.
* **Message Mapping functions** (custom functions such as `def String getHeader(String name,
  MappingContext context)` or `def void f(String[] values, Output output, MappingContext context)`):
  pick the function under **Function** above the editor. It lists the script's functions in two groups:
  *Script step* (gets the message) and *Message Mapping function* (gets field values), each with its
  parameter names; **Other name…** takes any other name, e.g. from an iFlow step. The list is read from
  the script text when the script opens, so nothing runs for it; after a run, the engine's list wins where
  they differ (the reading of the text is a heuristic and may miss unusual declarations). For a mapping
  function the left pane shows **Function arguments** instead of the body: a field per `String` parameter,
  named as in the script (**null** passes null), and per `String[]` parameter the values of the source
  field as the mapping passes them in: one box per context (a context change starts the next one, e.g. per
  parent element), with **+ Value**, **+ Context** and per value *text* (the field's value), *SUPPRESS*
  (a suppressed value: no content, but still a place in the context), *xsi:nil* or *null*. **Text** shows the
  same as one context per line in JSON (`["A", "B"]`; `{"suppress": true}` and `{"xsiNil": true}`). `Output` and `MappingContext` come from the simulator. A `String[]` function runs once
  per context, a `String` function once (a tenant calls it once per value). A script with only mapping
  functions opens with its first one;
  each script keeps its function and arguments while the app is open, also when you switch to
  another script or tab and back. **Result** shows the returned
  value or the output queue, one row per result context (**Text** shows it as JSON); `MappingContext` reads and sets the Headers and Properties tabs. The
  mapping itself (`.mmap`) is not simulated. SAP does not support Groovy Script 2.x in Message
  Mappings, and a tenant ran mapping scripts on Groovy 2.4.21 and Java 21, so use 1.x
  ([Fidelity](fidelity.md)).
* **Lint** shows hints about what breaks when a script moves from 1.x to 2.x:
  * `XmlSlurper` or `XmlParser` used without a `groovy.xml` import,
  * Script API v1 imports or v1-only methods in a 2.x step,
  * wildcard imports.

**✕** closes the project in the engines too, so runs no longer see its files or `platform/` data.

### A message from a tenant's trace (CPI Helper)

The [CPI Helper](https://github.com/dbeck121/CPI-Helper-Chrome-Extension) browser extension can hand a traced
step's message to an external IDE: its **GroovyDebugX** plugin for a Groovy Script step, **xsltDebugX** for an
XSLT Mapping step, each with the body, headers and properties before the step and, if you tick it, the step's
script or mapping. CPI Workbench takes that hand-over. It goes one way, into the app: CPI Workbench sends
nothing and calls nothing for it, neither the tenant nor CPI Helper. The extension, in your browser, has the
data and puts it into a link; the app only reads that link. CPI Helper is optional, too: **Import…** takes a
message you write as JSON just as well.

* **Desktop app, one click:** under **⚙** turn on *Receive links from CPI Helper*; the app then listens on this
  computer only (127.0.0.1, port 7790 unless you change it) and shows the *Custom URL* for each plugin:
  `http://127.0.0.1:7790/#share/` for GroovyDebugX, `http://127.0.0.1:7790/` for xsltDebugX. Enter it in
  CPI Helper's plugin settings. When you send a step, the plugin's tab hands the message to the app and says so,
  and the app asks before it imports it: the dialog names the tab, the body's size and what it replaces. Any web
  page can open such a link, so the dialog does not claim it came from CPI Helper; check what it shows. If the
  app was not running, the tab shows the browser's error page with the message still in its address: start
  the app and reload the tab. The setting is off by default, and then the app opens no port. A message may hold
  up to 128 million characters unpacked; importing it runs nothing, not even the open script or mapping with
  **Live** on: press Ctrl+Enter to run it.
* **Desktop app, by hand:** use the same local *Custom URL* values above, send the step, then copy
  the browser tab's address into **Import…** above the Groovy or XSLT input. This works with the
  receiver off too: an error page still has the link in its address.
* **Browser mode:** let the plugins open CPI Workbench directly. In CPI Helper's plugin settings choose
  *Custom URL*: for GroovyDebugX `http://127.0.0.1:7777/#share/`, for xsltDebugX `http://127.0.0.1:7777/`
  (with the port, and the `?engines=…` part before `#`, that you start the browser mode with). Each
  hand-over opens a new tab with the message in it.
* The message lands in its tab: body, headers and properties replace what is there, parts you did not send
  stay. A script or mapping that comes along is unsaved text (**Save** asks for a path) and does not run
  until you press **Run**. With a pasted or browser-mode message that contains no code, the existing
  script/mapping may re-run when Live is enabled. The desktop receiver is stricter: every import
  disables automatic runs until you press **Run**.
* A body that starts like XML or JSON and does not parse gets a warning (in the dialog, and in the red bar after
  the import): a large traced body can arrive cut off before CPI Helper builds the link. It is imported anyway.
  A body of more than 64 million characters is not checked.
* A trace's values are text, so all headers and properties arrive as *String*; set another type in the table
  where the script needs it (`getHeader(name, Integer)`).
* **Import…** also takes a message as JSON: `{ "body": "…", "headers": { "Name": "value" }, "properties": { … } }`.
* Treat the link like the message: traces may contain confidential business data. Use a local target
  (`127.0.0.1`) to keep the hand-over on this computer. URL fragments are not sent in the browser's HTTP
  navigation, but the destination page can read them; the receiver page deliberately POSTs the fragment
  to the local app. An external target can therefore receive the data even when its URL uses `#share/`.
  GroovyDebugX's default GroovyIDE and Contiva targets also put data in the URL path or query.
* Not read: GroovyDebugX's *Contiva IDE* format; choose *Custom URL* or *GroovyIDE* in the plugin. On macOS
  before 13.3 the desktop app cannot unpack a link (its web view lacks the function); use the browser mode
  there or paste JSON.

The link formats are the plugins' own, read from their source code in November 2026 and tried with a tenant
(GroovyDebugX and xsltDebugX, body and script or mapping); they are not documented and may change. The link,
with the message, can stay in the browser's history.

### What a run can access

* XSLT, XSD and XPath runs read files inside the open project only: includes, `doc()`,
  `document()`, `unparsed-text()`, external entities and DTDs. The network is blocked. A symbolic
  link inside the project that leads outside it counts as outside; links within the project work.
  `xsl:result-document` and `collection()` are not supported. A run fails when it asks for a file
  outside the project, on both Saxon versions (Saxon 9.9 alone would go on with an empty `document()`). XPath runs (the XPath tab and flow
  steps) also refuse `fn:transform()`, and on Saxon 12 `fn:function-lookup()`: with a configuration of
  its own, a nested transformation would not be bound to the project. In XSLT, reflexive `java:` calls
  and `fn:transform()` are exceptions to the project file access rules. Reflexive Java calls are
  available only on a manual run, not in Live previews.
* XPath runs see no environment variables: `environment-variable()` returns nothing.
* Groovy scripts run as ordinary Java code with your user's rights; there is no sandbox. Run only
  scripts you trust.
* Camel Simple (Router conditions, Content Modifier expressions, `${...}` in other flow steps) can call
  Java methods on its values, so it is not restricted either. Flow steps run only when you press **Run step**.

The installers are not code-signed yet; [Installing](install.md) shows how to start them the
first time. The Windows `setup.exe` installs per user and needs no admin rights; the `.msi` installs
per machine and needs admin rights.

The Groovy runtime starts on demand and stops after 10 idle minutes. The next run still uses the selected
project and its mock data. If a worker stops during a run, start the run again yourself; the app does not
replay it. Closing the app stops all engines; if the app ends without closing, the engines stop on their own.

**⚙** (bottom left, next to the status) shows the installed version. **Check for updates** asks github.com for the latest
release and links to its page if it is newer; download and install it from there.
If GitHub refuses, the menu shows its reason (without sign-in GitHub allows 60 requests per hour per
network address, which a shared company network can use up) and **Open releases page**. Apart from your
own scripts, this is the app's only external network request, and it is made only when you click.
The optional CPI Helper receiver accepts browser traffic on loopback only; it never contacts a tenant.

## Coding agents (MCP)

`cpi-workbench mcp` gives an agent such as Claude Code or Claude Desktop the app's tabs (XSLT, Groovy, XSD,
XPath, Flow Steps, Where used, Diagram) over the Model Context Protocol, plus reading and saving project files,
importing an iFlow and a reference of the Script API, so it can write a step, try configurations on a sample
message and follow a message through an iFlow on the simulator. With `--workspace`, one configuration serves a
folder of projects, one per integration. Setup, tools and what the server can access: [MCP server](mcp.md).

## Command line

```sh
java -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in input.xml -H SenderSystem=ERP
java -jar cpiwb-xml-he99.jar xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
java -jar cpiwb-xml-he99.jar validate --xsd schemas/order.xsd --in output.xml
java -jar cpiwb-xml-he99.jar xpath --expr "//Item/@sku" --in input.xml --version 3.1
```

These examples use the build-output jar names; release jars include `-<version>` before `.jar`.
Native XML executables (`cpiwb-xml-he99`, `cpiwb-xml-he12`, `.exe` on Windows) take the same commands.
Use `--project <dir>` to choose the resource root; otherwise it is inferred from the script/mapping.
`--json` returns the full result with diagnostics instead of terminal summaries. Direct `run`/`xslt`
commands do not have the shell's supervised timeout; use the app/MCP or a supervised `serve` process
when running potentially non-terminating code.

`-H` sets a header and `-P` an exchange property, as `name=value[:Type]`. The types are listed in
[Project files](project-files.md#header-and-property-types).

## Browser mode (no desktop shell needed)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode listens on localhost only and answers only requests that name this machine (`localhost`,
`127.0.0.1` or `[::1]` in the Host header, and a local origin), so a foreign web page cannot drive an
engine, not even one whose name is re-pointed to 127.0.0.1 (DNS rebinding). The engine that serves the
page also handles the project's files. Each tab sends its own project with each call, so opening or closing
a project in one tab does not change another tab's project. Tabs opened later can still restore the last
project from browser settings. A browser request can carry up to 32 MiB, including JSON and Base64
encoding. Use the desktop app or a smaller message if a request exceeds this limit. A request waits while the
engine runs another one (for example a save during a long run); if more requests are waiting than the engine
admits, or the wait exceeds the run timeout plus five seconds, it is refused without running it. Try again
when the active call finishes. Only a timeout during execution restarts the engine; the next call
carries the project as usual. Failed calls are not retried automatically.
To add more engines, use jars from the same build as the UI and pass them in a URL parameter (local engines only):

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```
