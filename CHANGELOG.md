# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). A release takes its notes from the section
of its version.

## [Unreleased]

### Added

- **MCP: a workspace of projects.** `cpi-workbench mcp --workspace <folder>` serves the folders inside that folder,
  one per integration, from one entry in Claude Desktop or Codex: `project_info` lists them, and the new tool
  `open_project` opens one or creates a new project there. Before, each project needed its own `--project` entry.

### Changed

- **MCP: Where used as in the app.** `iflow_where_used` without a query returns the tab's overview (what the
  iFlows use, with counts, and their parameters); a search resolves `{{parameters}}` from `parameters.prop`, shows
  a Content Modifier table as its matching rows and lists the parameters whose name or value contains the query.
  The app's overview now sorts values of equal count ignoring case on every machine, rather than by the system
  language, so that both give the same order.
- **MCP: an iFlow's diagram as text.** The new tool `iflow_diagram` lists an imported iFlow's processes with their
  elements in flow order, a Router's routes with their conditions, Process Calls, each element's settings and the
  adapters with their systems, as the Diagram tab and its outline show them; each element on a route names it
  (`onRoute`), so an agent can follow a message through the iFlow.
- **MCP: descriptions for agents that do not know the app.** The tools, their parameters and the server's
  instructions explain what they referred to the app's tabs for (the Groovy body type, the XPath version, a
  Router's condition types), how to try another configuration of an iFlow's step on the same input, how a
  Router's result passes the message on, and when to use `iflow_diagram` and `iflow_where_used`.
- **MCP: a script's functions.** `read_file` on a Groovy script lists the functions it declares, read from its
  text as the Groovy tab does (nothing runs), and `project_info` marks a script step whose function the script
  does not declare (`functionFound: false`), as does `import_iflow`. A run's list of Message Mapping functions is
  called `mappingFunctions` in `run_groovy`, so it is not taken for all of the script's functions. `open_project`
  no longer repeats the runtimes.
- **A missing script function names the others.** When a Groovy Script step's function is not in the script, the
  error lists the script's functions with one parameter, in the app and over MCP.
- **A missing file is named as such.** Reading a file that does not exist (a body, script or mapping by path, in
  the app and over MCP) said only its path; it now says `No such file: <path>`.
- **MCP: every result message names its `runId`.** A Splitter's messages and each runtime of `runtime: both` carry
  their own `runId` (`run-…/2`, `run-…/saxon-he-12`), so an agent no longer builds it from the convention.

## [0.7.0] - 2026-11-24

### Added

- **Import iFlow without a project.** The start screen offers **Import iFlow…** next to *Open folder…*: it asks for
  the exported zip, then where to create a new project folder (named after the zip), creates it with the standard
  layout and imports into it. A folder that exists already is refused.

### Changed

- **Where used opens with an overview.** Instead of an empty search, the tab lists the addresses, credentials,
  queues, key aliases and files the imported iFlows use, with the number of places each (a click searches for it;
  files are folded), and below them each iFlow's externalized parameters, which had their own *Parameters* view
  before. A value with a `{{parameter}}` shows what `parameters.prop` resolves it to, in the overview and the hits.
  A search also lists the parameters whose name or value contains it, with their uses, and a hit in a Content
  Modifier table shows only the matching rows as cells instead of the table's XML text.
- **Where used: receivers named by their caller.** A receiver adapter's hits name the step that calls it
  (`SuccessFactors receiver from Get WorkOrders`), so several receivers of one system can be told apart. The MCP
  tool `iflow_where_used` returns the same label.
- **iFlows that share a name** (a copied iFlow keeps its name) are told apart by their folder in Where used, the
  Diagram's iFlow picker and the sidebar's iFlow list.
- **Editor features.** The code editors (Monaco 0.57) now offer Monaco's full set of editor features in every
  editor: its own right-click menu, the command palette (F1), sticky scroll of enclosing elements and
  go-to-line, among others. Monaco 0.57 loads these with its JSON support anyway, so before, editors opened
  after the first JSON file had more features than earlier ones.
- **Dependencies.** The app's UI and shell dependencies are updated: Vite 8, the Svelte plugin 7, TypeScript 6,
  Monaco 0.57, Svelte 5.57.3, the Tauri dialog plugin 2.8.4, and the Rust crates within their versions
  (Windows: `junction` 2). The engines' runtimes (Saxon, Groovy, Camel, Java) are unchanged.

### Fixed

- **Where used: parameters in a Content Modifier table.** A parameter whose name has a quote or another escaped
  character, used in a header or property table, was listed twice: once escaped (`&apos;`) with *no value*, once as
  *unused*. It is now one parameter with its value and uses.

### Security

- The desktop shell's Rust dependencies include `url` 2.5.9 and `cookie` 0.18.3, which fix RUSTSEC-2026-0592 (a stack
  overflow on nested `blob:` URLs) and RUSTSEC-2026-0594 (repeated private-cookie nonces after a fork).

## [0.6.0] - 2026-11-19

### Added

- **Fidelity indicator.** Next to the runtime and next to *Run step* in Flow Steps, a button says how well the
  open step's behaviour is backed: tenant-verified, documented or partly an assumption. A click lists the rows of
  the [Fidelity](docs/fidelity.md) page for that step, which now gives each row the steps it applies to and its basis.
- **Where used.** A new tab next to *Diagram* searches all imported iFlows offline for a host, credential or key
  alias, queue, file, parameter or any value, and lists each iFlow's externalized parameters with value, type,
  description and uses, marking unused ones and ones without a value. The ⌕ next to a diagram setting searches
  for it. A sender or receiver in the diagram now also shows its adapters' settings. Over MCP: the tool
  `iflow_where_used`. Settings are searched as the export writes them (`{{SftpHost}}`, not its value); an
  unreadable `parameters.prop` is shown as that iFlow's error.

### Changed

- **Test cases are internal.** `case.json` test cases are this repository's regression tests and no longer a
  documented user feature; a step is tested by running it in the app. *Init* no longer creates `tests/`. The
  project layout, header types, script libraries and the `platform/` formats moved to the new page
  [Project files and platform data](docs/project-files.md); the `case.json` format stays in
  [Regression test cases](docs/test-cases.md) for contributors.

### Fixed

- **A flow step from an iFlow without some settings** now runs with the step's defaults in the app, as it already
  did over MCP: an Encoder or Decoder without a scheme (Base64), a Message Digest without canonicalization, algorithm
  or target header, a Splitter without its type or expression type, a Filter without its value type, a Gather
  without its message type or algorithm. Before, the import filled them with empty values. Whether CPI
  uses the same defaults is not known: the app names them under the step's *From* line, and the MCP server returns
  them as `step.defaulted`.
- **Converter lists over MCP**: an iFlow's namespace mapping and array paths reach the engine as the app sends them,
  one entry per line; a namespace pair without prefix or namespace is left out, as before. The Flow Steps tab and the MCP server now replay shared fixtures in their tests
  (`testdata/flow-parity`), so the two cannot drift apart unnoticed.
- **XSLT Live previews** cannot execute reflexive Java calls, including calls in imported or included
  stylesheets. Use **Run** or **Ctrl+Enter** each time you want to run a mapping that needs Java.
- **Editor saves** keep edits made during a write marked as unsaved. A late Save or Save As does not
  change the file shown in an editor that was closed or reopened meanwhile.
- **Browser projects** are independent per tab. Files and simulations use the project selected in that tab,
  even if another tab opens or closes a different project on the same engine.
- **New file destinations** require overwrite confirmation even if the destination is not visible in the
  project tree or another process creates it just before the write.
- **Desktop engine restarts** no longer lose the selected project. The first run after an idle stop or
  worker exit still sees project resources and Groovy platform mocks; a failed run is not replayed.
- **Content Modifier** evaluates each row once, on one Camel exchange, including when a later XPath
  row reads a value such as the exchange ID. A failed row stops later rows in the UI and MCP.
- **Content Modifier** preserves binary bodies byte for byte when Message Body is empty. *Use as input*
  and MCP `messageFrom` pass the bytes and updated headers and properties to the next step.
- **Content Modifier** warns when a body Expression or a `java.lang.String` row reads a binary body
  that is not valid text in the message charset, instead of silently replacing those bytes with U+FFFD.
- **XSLT Live previews** also ask for a manual Run when a `java:` namespace is written with character
  or entity references or as an EQName (`Q{java:…}`); other compile errors are reported as compile errors.
- **Browser requests** have bounded uploads and queue waits. A waiting request outlives the call ahead
  (a save during a long run waits for it); one the engine cannot admit is refused without interrupting the
  request already running.
- **Imports** no longer report a finished import as failed when a temporary file cannot be removed
  (a virus scanner holding it on Windows). A failed create-new import cleans up only its own writes;
  files added by another writer are left alone. Archives with duplicate entries are refused with the
  entry's name. Overwrite imports list the files they keep because the new export no longer has them (`kept`).
- **iFlow discovery** reads an iFlow reached through a link to another project folder once, not twice,
  in Steps, Diagram and Where used.
- **Engine info** and calls that need no project files still work after the open project folder was
  deleted or renamed; calls that need it report *Project folder is unavailable*.
- **Project Init and iFlow discovery** now refuse paths that lead outside the selected project through
  a symlink or Windows junction. Init leaves existing files intact; iFlow scans report refused paths.
- **Confirmation dialogs** keep keyboard focus inside, select Cancel first for confirmations, and
  return focus to the control that opened the dialog.
- **Flow Steps** works with either engine family installed and explains missing runtimes for the
  selected step and settings.
- **Filter** expands `function:simple(...)` calls in the original XPath only. Call-like text returned
  by an expression stays literal, in the UI and MCP. So does call-like text in the XPath's own string
  literals and `(: :)` comments.
- **Save as…** onto the editor's own file saves it without asking to overwrite. A Platform table save
  that updates the same file in the File tab no longer lets a File tab save still in flight mark older
  content as saved.
- **Browser mode** no longer reports running engines as "No compatible engine connected" after the open
  project folder was deleted or renamed; it says the folder is unavailable and to reopen it.
- **Confirmation dialogs** opened from a menu that has since closed (such as *Rename…* in the file tree)
  return focus to the active tool tab instead of the page. Without native modal dialogs, the page
  behind the dialog is inert.
- **Imports** stage and validate all archives before writing project files, enforce aggregate expansion
  limits, and publish Value Mapping tables by atomic replacement.
- **MCP** bounds concurrent requests, queued replies and retained run data. A result that exceeds
  the preview or run-cache limit is reported explicitly.
- **Desktop and MCP workers** have separate queue, startup and pipe deadlines. A stalled worker is
  stopped and reaped without replaying its request.
- **Desktop file calls** wait for a long run on the same engine instead of failing with "Engine busy".
  A request over the 192 MiB engine frame limit is refused with `UnsupportedInSimulator` without
  stopping the engine.
- **MCP** frees a request id before sending its answer, so a client can reuse it at once. A run too
  large for the run cache returns its shortened result without a `runId` (`runNotice`), and a result
  over the 1 MiB preview is shortened (`resultShortened`) instead of reporting a completed import as failed.

## [0.5.0] - 2026-11-17

### Added

- **Import of Value Mappings and integration packages.** *Import iFlow* (and the MCP server's `import_iflow`) also
  takes an exported Value Mapping, whose groups become rows of `platform/value-mappings.json` in both directions
  (the value marked default first), and a whole package export with its iFlows, script collections and value
  mappings. A Groovy step whose script is in a script collection finds it in that collection's folder. Artifacts
  whose folder names would collide (also ignoring case and trailing dots, as Windows does) refuse the import
  before any file or value mapping is written, even when overwrite is on.
- **iFlow outline and Diagram tab.** After an import the sidebar lists each iFlow whole: adapters, processes,
  exception subprocesses and every element in flow order, with the Router's routes and conditions. The new
  *Diagram* tab draws the iFlow in the layout of its export (read-only); steps the app runs open from both.
- **Examples without a project.** *Examples…* in the sidebar loads a mapping or script of the demo project with
  its input; they come with the app.
- **Debugging Groovy scripts.** `docs/debugging.md` shows how to stop a script at a breakpoint: an engine jar
  under Java's debug agent, with jdb, IntelliJ or VS Code attached.
- **Run history.** The XSLT and Groovy output has a *History* tab with the last runs of this session and
  *Compare with the previous run* (body, headers, properties), in memory only.
- **Test cases: `"compare": "json"`.** Compares JSON bodies as values (member order and formatting do not count),
  and `expect.ignore` takes JSON Pointers there (`/order/items/*/uuid`).
- **Flow Steps: Message Digest.** The message, or the one element a filter XPath selects, is written as canonical
  XML (the six methods CPI offers, or *None*) and its digest (MD5 to SHA512) goes to a header, `SAPMessageDigest`
  by default, as Base64 text. The result also shows the canonical form the digest is of. In the tab, in the MCP
  server's `run_flow_step` (the header travels on with `messageFrom`) and from an imported iFlow. Not tried on a
  tenant yet: *None* hashing the body's bytes is an assumption, and a filter that selects several nodes is refused.
- **Flow Steps: Encoder and Decoder.** Base64, GZIP and ZIP in both directions, as Camel 3.14.7's data formats do
  them with their defaults (Base64 in lines of 76 characters with CR LF; ZIP's entry named after `CamelFileName`).
  That CPI's steps behave the same is not verified on a tenant yet, and the result says so. MIME Multipart is not
  simulated.
- **Flow Steps: binary bodies.** A result that is no text (GZIP, ZIP) shows its size and a hex dump; *Use as input*
  passes the bytes to the next step and *Save…* writes them to a file. A `.zip` or `.gz` file of the project is
  listed in the file tree, wherever it is, and opens from there as a binary input body. The MCP server passes such
  a body on by `runId` and as `bodyPath`, `read_file` returns bytes with `binary: true` and `save_file` writes them
  (`contentBase64`, or `contentFrom` a binary run). A Groovy script gets a body that is no text as `byte[]` instead
  of garbled text.
- **Flow Steps: a step opened from an iFlow can be closed.** The **×** after *From <iFlow> · <step>* gives the
  step its empty starting settings back and drops its result; the iFlow is not changed. Closing or switching the
  project closes the steps taken from its iFlows.
- **Test cases: a JUnit XML report.** `test <path> --junit <file>` on each engine also writes the results as a
  JUnit XML report for a CI system: one test case per case, failed cases with their messages, the cases of other
  engines as skipped. The output and the exit code stay as they are. The layout is Maven Surefire's; it has not
  been tried with a CI reporter yet. See [test cases](docs/test-cases.md).
- **Test cases: leave out values that change with every run.** `expect.ignore` lists XPath expressions (XPath 1.0)
  whose nodes are removed from the expected and the actual body before they are compared as `xml`: a timestamp, a
  generated id. `expect.namespaces` binds the prefixes the expressions use. A rule that cannot apply (no XPath,
  an unknown prefix, a comparison as text) makes the case an error. XML bodies only.

### Changed

- **CPI Helper receiver: the default port is 7790**, no longer 7778. 7778 is where the Groovy 1.x engine of the
  browser mode listens (and the Groovy engines' `--http` default), so the two could not run side by side. If you
  turned the receiver on in 0.4.0, it stays on 7778 and the *Custom URL* in the plugins keeps working; to move,
  set the port under **⚙** and enter the new *Custom URL* in both plugins.
- **The app starts empty.** Input bodies, headers, the mapping and script editors, the XPath expression and the
  flow steps (Content Modifier rows, Router routes, Splitter, Filter and the converter's source path) no longer
  hold sample values; without a project, **Examples…** loads something to try. The MCP server's converter has no
  default source path either.
- **Flow steps taken from an iFlow close with their project.** Closing or switching the project used to leave their
  settings and *From* line in the Flow Steps tab.

### Fixed

- **iFlow and package import** refuse a folder that differs from one already in `iflows/` only in letter case
  or trailing dots (on Windows that is the same folder), and check an artifact named `.` or `..` before the
  package's value mappings are written.

## [0.4.0] - 2026-11-16

Workflow in the tabs: platform data as tables, a traced message from CPI Helper (pasted, or with one click
into the desktop app), XPath matches in the document, Message Mapping functions with a picker, closing files,
and chaining runs in the MCP server. The CPI Helper hand-over was tried with both plugins on a tenant
(Windows desktop app).

### Added

- **XPath tab**: the selected nodes are marked in the document and a result jumps to its node; **XPath at cursor**
  writes the XPath of the element or attribute at the cursor (this node, or all like it) with the Namespace
  mapping's prefixes; **↑** / **↓** in the expression field bring back the session's expressions.
- **A message from a tenant's trace, through CPI Helper.** The browser extension's GroovyDebugX and xsltDebugX
  plugins hand a traced step's body, headers and properties (and the script or mapping) to an external IDE;
  CPI Workbench takes that link: **Import…** in the Groovy and XSLT tabs (paste the link), or directly in the
  browser mode with the plugins' *Custom URL* set to it. It goes one way: the app only reads the link and still
  connects to nothing, and an imported script or mapping does not run until you start it. See the [user guide](docs/user-guide.md#a-message-from-a-tenants-trace-cpi-helper).
- **One click from CPI Helper into the desktop app.** Under **⚙**, *Receive links from CPI Helper* (off by default)
  lets the app listen on this computer only (127.0.0.1, port 7778) for the link the GroovyDebugX and xsltDebugX
  plugins open; the settings show the *Custom URL* to enter in each plugin. The app asks in a dialog before it
  imports the message, and nothing runs by itself, not even the open script or mapping with **Live** on.
- **A cut-off body is pointed out**: a body imported from a link or as JSON that starts like XML or JSON and does
  not parse gets a warning; a large traced body can arrive cut off before CPI Helper builds the link.
- **Groovy tab: platform data, attachments and SOAP headers as tables.** The new **Platform** tab edits value
  mappings, Secure Store credentials, Partner Directory parameters and number ranges from the project's
  `platform/*.json` files; a change applies to the next run at once and **Save** writes the file. **Attachments**
  and **SOAP** hold what the script reads from the message; before, only a test case could bring them.
- **Close an open file** with the × next to its name in the mapping, script, schema, input and File panes,
  and on the File tab in the tool bar.
  The file on disk stays as it is; unsaved changes are asked about first. A closed mapping, script or schema
  leaves an empty editor whose **Save** asks for a new path, and a closed input empties the input body.
- **MCP server for Claude Desktop and other agents without file access**: `read_file` reads a project file
  (long files in parts), `save_file` writes one (an existing file only with `overwrite`), `import_iflow` imports an
  exported iFlow, and `reference` explains the Script API, the `platform/` data formats and what is verified
  against CPI, generated from the simulator's sources. The server's instructions say how to develop a step with the
  tools, and the prompt **develop_step** starts it. See [docs/mcp.md](docs/mcp.md#working-from-claude-desktop).
- **MCP: pass results on by `runId`**: every run that produces a message returns a `runId`; `messageFrom` makes it
  the next step's input (body, headers and properties), a Gather takes a Splitter's messages with `messagesFrom`.
  A body the step did not change is left out of the result, and long bodies come back shortened; `read_file` with `runId` and `save_file` with `contentFrom`
  take the whole body, `read_file` with `startChar` also a body that is one long line. A Router that raises an
  exception has `ok: false` and passes nothing on; a route without a name is shown as *Route n*.

### Changed

- **Groovy tab, Message Mapping functions**: a **Function** picker lists the script's functions as *Script step*
  or *Message Mapping function*, with their parameter names, and replaces the **fn** field, the mode label and
  the **Run as** bar. The functions are read from the script text when it opens, so a mapping script shows its
  arguments before the first run (still without running it). The arguments carry the parameter names from the
  script; a `String[]` argument is edited as the source field's values grouped by context (**+ Value**,
  **+ Context**, per value text, SUPPRESS, xsi:nil or null), with **Text** for the JSON form of `case.json`.
  **Result** lists the output queue as one row per context.
- **Gather**: Combine warns when a message is not a well-formed XML document (JSON or text from a script), and
  separately when the combined result is not well-formed XML; it still runs as before. What CPI does in that case is open; `probes/gather/` asks a tenant.
- **Documentation refreshed:** current scope and verification gaps replace estimated completion
  percentages and stale test counts; completed roadmap drafts are condensed, and build, security,
  CPI Helper and platform-library guidance is aligned with the current implementation.

### Fixed

- **Gather's Combine at XPath, the Splitter and XPath results** write XML for a document whose root is an
  unnamespaced `<html>`; the Java XML writer switched to HTML there (`<br>` unclosed, a `META` element added).
- **XPath 1.0** knows the `xml` prefix without a Namespace mapping row (`//@xml:lang`), as 3.1 does.
- **Number ranges**: `min`, `max`, `next` and `fieldLength` are read exactly (`9007199254740993.0` and `5e3`
  included); a fraction or a value beyond a long is an error naming the field. Before, it was cut or wrapped
  without a word (a `max` of `99999999999999999999` became 7766279631452241919).
- **Flow Steps, Router**: a true route with an empty name was reported as "No route matched" (and raised the
  exception with *Throw Exception*); it is taken and shown as *Route n*.
- **Splitter and Gather on Windows**: split messages, a Gather's Combine at XPath, an XPath's element results and
  a script's SOAP headers in the result use LF line breaks, as on macOS and Linux; they had CRLF from the Java XML writer.
- **Groovy: an attachment given as a file while no project is open** is an error that says so; it ran as an
  empty attachment before.

## [0.3.1] - 2026-11-11

Fixes from a review of the whole app (2026-11-10), and the Groovy tab keeps a script's settings.

### Removed

- **Linux installers** (`.deb`, `.rpm`): releases bring installers for Windows and macOS only. On Linux,
  use the engine jars or the browser mode, or build the desktop app from source (docs/building.md).
  CI no longer builds the desktop app or runs the browser e2e tests on Linux; the engine tests still run there.

### Security

- **Browser mode**: an engine served over HTTP answers only requests that name this machine in the
  `Host` header and come from a local origin. Before, a web page whose name was re-pointed to
  127.0.0.1 (DNS rebinding) could open any folder, read and write files and run Groovy. `?engines=`
  takes engines on this machine only. The desktop app was not affected.
- **Opening a file no longer runs its code**: a Groovy script, a Groovy test case and a stylesheet with
  reflexive `java:` calls run for the first time when you press Run or Ctrl+Enter; live runs follow
  after that. Before, a click on a script of an imported iFlow ran it with your rights.
- The installed app looks for engines only next to itself and in `CPIWB_ENGINE_DIR`, no longer in
  `engine/…/target` of every folder above it (debug builds still do).
- A script or attachment path that is a symbolic link out of the project is refused by the Groovy
  engines too. XPath runs see no environment variables.
- CI: the GitHub Actions are pinned to commits instead of tags, and jobs get read-only access to the
  repository (the release job alone may write).

### Fixed

- Groovy: clicking the open script in the file tree again (e.g. after a visit to another tab) no longer
  resets it to `processData` and drops the arguments of its mapping function. Each script keeps its
  function and arguments for the session, so they are back when you return to it from another script.
  A script with only mapping functions runs its first one on its first run instead of failing on `processData`.
- **Messages that name an encoding**: a body starting with `<?xml version="1.0" encoding="ISO-8859-1"?>`
  came out as `MÃ¼ller` in XSLT, XPath, XSD, the converters and a script's `InputStream`, and **Format**
  wrote that back into the editor. The body now reaches the parser in the declared encoding;
  Format keeps the declaration. Files in such an encoding open (they failed with
  "Input length = …") and are saved in it. A character that the declared encoding cannot hold (`€` in
  ISO-8859-1) is an error, in a run and when saving, instead of a silent `?`.
- **Test cases**: a case with a misspelt profile was skipped by every engine and never ran; it is an
  error now. `test <path>` fails when it finds no `case.json`, and with `--fail-if-none` when the
  engine ran no case. `errorContains` finds texts with quotes or backslashes.
- **Rename / Copy** to the file's own name in another spelling (`./a.xsl`, or another case where the
  file system ignores case) deleted the file; it is refused now.
- XSD: a problem in the input's external entities or DTD is reported for the input, not as "Schema
  cannot be loaded". Saxon 9.9 reports why `doc()` was refused instead of "Exception thrown by URIResolver".
- A test case's attachment file is found when the project path is written with `..`.
- XSLT on Saxon 9.9: a `document()` outside the project fails the run, as on Saxon 12; before, the run
  went on with an empty result and a warning.
- **Browser mode**: after a timed-out call the engine starts again by itself, as in the desktop app;
  before, it stopped and had to be started by hand. `serve --http` keeps the JVM options it was started
  with, and the engine ends with the command that started it.
- **Init** writes valid JSON for a folder name with quotes.
- CI runs the regression cases on Saxon-HE 12 too.

## [0.3.0] - 2026-11-08

### Changed

The probes ran on a tenant for the first time (2026-11-09, results in `probes/results-tenant/`), and
the Groovy engines now follow what they showed:

- **Groovy Script 1.x runs on Camel 3.14.7**, like 2.x and like a tenant. Camel 2.24 is gone, and with
  it the *Camel* choice in the Flow Steps tab: tenants on software 6.x / 5.x (Camel 2) are no longer
  covered. Camel Simple rewrites CPI's `and` / `or` / `${property.x}` on both engines.
- **Java 21**: the app's bundled Java runtime is 21, as on a tenant (was 17). The engine jars still
  start on Java 17.
- **UTC and `en_US`** in every engine, whatever the computer uses, as on a tenant: dates,
  `current-dateTime()`, `implicit-timezone()` and case conversion no longer depend on the computer.
- **Script context** as on a tenant: `javax.script.filename`, and on 1.x `log` and `logger`, SAP's
  `ScriptLogger` with `logInfo`, `logWarnings` and `logErrors` (they print to the Console) and no
  `info` or `debug`: `log.info(…)` fails as on a tenant. No `out` variable.
- **Message methods** as on a tenant: the 1.x message has the overloads with Camel's `Attachment`,
  `getAttachmentObjects` and `setAttachmentObjects` (`AttachmentWrapper` is a Camel attachment); the
  2.x message has `getSoapHeaders`, `setSoapHeaders` and `clearSoapHeaders` and receives the input's
  SOAP headers. The lint no longer warns about the SOAP header methods on 2.x.
- **Libraries** scripts can load, in the versions a tenant reports: commons-lang3 3.20.0 (1.x and
  2.x), Jackson databind 2.21.7 and commons-codec 1.16.1 (1.x), Groovy's yaml and toml modules (2.x)
  and Camel's `org.apache.camel.attachment` (both). The fingerprint probes report library versions,
  the types of the bound variables and the methods of `log`.
- **Probe files** for the Message Mapping probe (`probes/mapping/`: source and target schema,
  requests) and `curl` commands for sending the probe requests (`probes/README.md`).
- **Message Mapping functions**: a tenant runs mapping scripts on Groovy 2.4.21 and Java 21, calls a
  `String[]` function once per context (an empty context with an empty array) and joins the results with context changes, as the simulator
  does. Context change, SUPPRESS and xsi:nil now have a tenant's marker texts (`__cC_`,
  `_sUpPresSeD_`, `_!xSi#NiL$_`). `context.invalidateCache()`, which a
  tenant's `MappingContext` has, runs (it does nothing here, with a note); `output.getResultList()`
  fails with `UnsupportedInSimulator`. The mapping probe has a second function and a target with one
  element per source context.
- The top bar is plainer: no app name left of the tabs, and **▶ Run** is the only run button in the
  XSD Validation and XPath tabs (their *Validate* and *Evaluate* buttons did the same). The *Runtime*
  choice is gone from XSD Validation, where both runtimes use the same JDK validator, and is greyed
  out for XPath 1.0, which runs on the JDK.
- `$exchange` in XSLT prints as `Exchange[<id>]`, as on a tenant.
- **XML to CSV** writes what a tenant writes: an empty line after the parent element's lines, and a
  line break after the last line, and a value that contains the separator in double quotes (inner
  quotes are not doubled). **JSON to XML** and **CSV to XML** write an empty value as
  `<Name></Name>`, as a tenant does (JSON `null` stays `<Name/>`). All four converters now give the
  same bytes as a tenant for the probe inputs.
- Platform API messages and results follow the tenant: Partner Directory type and empty-ID errors,
  `getAuthorizedUsers` of an unknown partner is `null`, the pattern error, Secure Store errors for an
  empty alias and an unknown OAuth2 credential, the number range error. Without
  `platform/keystore.json` the Keystore is empty (lookups return null, key managers hold no key)
  instead of failing, and the run notes it. The simulator still adds where it looked.

### Added

- **Message Mapping functions** in the Groovy tab: a custom function of a mapping script, such as
  `getHeader(String, MappingContext)` or `f(String[], Output, MappingContext)`, runs on its own with
  *Arguments* instead of a body (`String[]` arguments one context per line, `String` arguments may be
  null); a `String[]` function runs once per context (*All values of a context*). *Result* shows the
  returned value or the output queue with context changes, SUPPRESS and xsi:nil. `MappingContext` and
  `Output` follow SAP's Javadoc. *Run as* above the editor lists a script's mapping functions, and a
  label next to *fn* says whether the function runs as a script step or as a mapping function. Test
  cases take `args`, `expect.returned` and `expect.queue`; the MCP tool `run_groovy` takes `args`.
  Graphical Message Mapping (`.mmap`) is still not simulated.
- **Check for updates** in a new ⚙ menu (desktop app, status bar bottom left): shows the installed version, asks github.com
  for the latest release on click, and opens its page if it is newer. If GitHub refuses (e.g. its hourly
  limit for requests without sign-in), it says why and offers the releases page. The app makes no request on its own.
- **Converter** step in Flow Steps: JSON to XML, XML to JSON, XML to CSV and CSV to XML with the
  settings of CPI's converter steps (namespace mapping, root element options, streaming, record marker,
  header mapping, parent element). It follows SAP's documented conversion rules and limitations; SAP's
  examples are reproduced in the tests (XML to CSV with the empty line and final line break a tenant
  writes). CSV to XML reads its XSD from the project. The iFlow import opens converter steps with their settings, and the MCP tool `run_flow_step` runs them.
- iFlow import lists **Message Mapping** steps. The mapping is not simulated; clicking the step opens
  the first Groovy script its `.mmap` uses, with its first mapping function, on Groovy Script 1.x.
- Probe `probe-mapping-v1.groovy` for how a tenant calls mapping functions, and converter inputs in
  `probes/converters/` for comparing the four conversions with a tenant. The Groovy probes read the
  Java version from the runtime MXBean (`java`, `javaVmVersion`): the tenant's script editor refuses
  `System.getProperty` as an unsupported class.

### Fixed

- An engine stops as soon as the app is gone, also when a Groovy script left a thread running and the
  app ended without closing (a crash, Task Manager). Before, such an engine kept running.

### Security

- Project path checks follow symbolic links: a link inside the project that leads outside it is
  refused for XML runs (includes, `doc()`, `unparsed-text()`, entities), the File tab, the MCP tools
  and the iFlow import. Links that stay inside the project still work. Before, the checks compared
  the paths as written.

## [0.2.0] - 2026-11-06

### Added

- **Flow Steps** tab: runs a Content Modifier, Filter, Router, General / Iterating Splitter or Gather
  on an input message (body, headers, properties, namespace mapping). *Use as input* passes a result
  on to the next step; each step keeps its settings and last result when you switch steps.
  - These steps have no Groovy version: *Camel* picks the tenant's Camel line (3.14, the default, or
    2.24). Camel Simple runs on the engines' real Camel; CPI's `=`, `and`, `or` and `${property.x}`
    work on both lines, and so do OGNL calls such as `${in.header.name.split(',')[0]}`.
  - XPath runs on Saxon (XPath 3.1) and sees headers and properties as variables (`$name`), as
    Camel's XPath variable resolver provides them.
  - Content Modifier: header and property rows top to bottom with CPI's source types and data types,
    Delete rows with `$include=` / `$exclude=` patterns, and the message body (Constant or Expression).
  - Filter: XPath with the Value Types String, Integer, Boolean, Node and Nodelist; SAP's
    `function:simple(...)` works.
  - Router: routes in order with XML or Non-XML conditions and a default route; the result shows
    which route the message takes and the Camel spelling of CPI's operators. Routes that mix XML and
    Non-XML conditions are an error, as in CPI.
  - General and Iterating Splitter: XPath, Line Break and Token, grouping and `CamelSplit*` values,
    with the rules of SAP's documented examples.
  - Gather: Combine in SAP's multi-mapping format, Combine at XPath and Concatenate. A splitter's
    result goes to Gather with one click; its messages are numbered body tabs in the input pane.
- Completions in Groovy scripts: the methods of SAP's Script API (v1 or v2, from the script's imports)
  after `message.`, `messageLogFactory.`, a message log and `ITApiFactory.`, with their parameters.
- iFlow import lists Content Modifier, Filter, Router, Splitter and Gather steps and opens them in
  Flow Steps with their settings and the iFlow's externalized parameters (`{{name}}` from
  `parameters.prop`). The body of a Content Modifier up to version 1.3 is read as an expression.
- **MCP server** for coding agents: `cpi-workbench mcp` lets an agent such as Claude Code or Codex use
  the app's tabs while it writes mappings and scripts. The tools `project_info`, `run_xslt`,
  `run_groovy`, `validate_xsd`, `evaluate_xpath` and `run_flow_step` take the tabs' inputs and make
  the same engine calls; the app does not need to be open. It speaks MCP 2026-07-28 and, for clients
  that open with `initialize`, 2025-11-25 back to 2024-11-05. Setup for `.mcp.json`, Claude Desktop
  and Codex, and what the server can access: [docs/mcp.md](docs/mcp.md).

### Changed

- The XPath tab starts with XPath 3.1 (Saxon), as SAP documents for the Content Modifier and the
  Filter. Its 1.0 option no longer claims to be what simple iFlow steps use; that had no source.
- The Groovy 2.x engine bundles Camel's `camel-bean`, which Camel 3 needs for OGNL calls in Camel
  Simple.
- The Groovy engines no longer print the JVM's class-data archive notes ("Skipping …: Old class has
  been linked") to stderr.

### Fixed

- Opening a flow step right after a Groovy step no longer logs an uncaught `Canceled` error from the
  editor in the browser console.
- XSD and XPath: when a run starts while another is still open (Ctrl+Enter), only the latest run's
  result is shown, as in the XSLT and Groovy tabs.
- XPath (tab and flow steps) refuses `fn:transform()`, and on Saxon 12 `fn:function-lookup()`: with a
  configuration of its own, a nested transformation could read files outside the project.

## [0.1.1] - 2026-10-26

### Fixed

- Windows: the installed app could not start the Groovy engines ("exited during start"), because
  the jar path reached Java with a `\\?\` prefix.
- Windows: the Groovy engines now also start when the install folder has characters Java cannot
  handle, such as a Polish or Chinese user name (JDK-8205991). The app reaches the folder through a
  junction in `C:\ProgramData\CPI Workbench <user SID>`. If that fails, the start error names the
  cause and the workaround.

## [0.1.0] - 2026-10-25

First public release.

### Added

- Desktop app for Windows, macOS (Apple Silicon) and Linux; browser mode served by the engines.
- **XSLT Mapping** on Saxon-HE 9.9 (CPI's release line) and Saxon-HE 12: CPI parameter binding from
  headers and properties, `$exchange` with `hci:setHeader` / `hci:setProperty`, includes from the
  project, reflexive `java:` calls to JDK classes, error lines, runtime comparison.
- **Groovy Script** 1.x (Groovy 2.4.21, Camel 2.24.3) and 2.x (Groovy 4.0.29, Camel 3.14.7) with
  clean-room Script API v1 and v2, message processing log, and mocks for Secure Store, Value
  Mapping, Partner Directory, Keystore and number ranges from the project's `platform/` folder.
- **XSD validation** and **XPath** 1.0 / 3.1.
- iFlow import (zip): lists the Groovy, XSLT and XML Validator steps and opens them with the step's
  settings.
- Project files: `inputs/` for input bodies, JSON inputs for Groovy, file tree context menu,
  Save as…, Format, resizable panes.
- Test cases (`case.json`) for the command line and CI, with the engine jars.
- XML runs read files inside the project only; `xsl:result-document` and `collection()` are not
  supported.
- The installers and engine jars include the licence texts and notices of the bundled components.

[Unreleased]: https://github.com/jsevenheck/cpi-workbench/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/jsevenheck/cpi-workbench/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.1.0
