# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). A release takes its notes from the section
of its version.

## [Unreleased]

### Added

- **Close an open file** with the × next to its name in the mapping, script, schema, input and File panes,
  and on the File tab in the tool bar.
  The file on disk stays as it is; unsaved changes are asked about first. A closed mapping, script or schema
  leaves an empty editor whose **Save** asks for a new path, and a closed input empties the input body.

### Changed

- **Groovy tab, Message Mapping functions**: a **Function** picker lists the script's functions as *Script step*
  or *Message Mapping function*, with their parameter names, and replaces the **fn** field, the mode label and
  the **Run as** bar. The functions are read from the script text when it opens, so a mapping script shows its
  arguments before the first run (still without running it). The arguments carry the parameter names from the
  script; a `String[]` argument is edited as the source field's values grouped by context (**+ Value**,
  **+ Context**, per value text, SUPPRESS, xsi:nil or null), with **Text** for the JSON form of `case.json`.
  **Result** lists the output queue as one row per context.

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

[Unreleased]: https://github.com/jsevenheck/cpi-workbench/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/jsevenheck/cpi-workbench/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.1.0
