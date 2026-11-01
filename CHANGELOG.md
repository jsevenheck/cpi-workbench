# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). A release takes its notes from the section
of its version.

## [Unreleased]

### Added

- **MCP server** for coding agents: `cpi-workbench mcp --project <folder>` serves the simulator over
  the Model Context Protocol (stdio), with the tools `project_info`, `run_xslt`, `run_groovy`,
  `validate_xsd`, `evaluate_xpath`, `evaluate_simple`, `run_flow_step` and `run_tests`. It speaks the
  2026-07-28 revision and, for clients that open with `initialize`, 2025-11-25 back to 2024-11-05.
  Setup and what the server can access: [docs/mcp.md](docs/mcp.md).

### Changed

- The Groovy engines no longer print the JVM's class-data archive notes ("Skipping …: Old class has
  been linked") to stderr.

## [0.2.0] - 2026-11-01

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

### Changed

- The XPath tab starts with XPath 3.1 (Saxon), as SAP documents for the Content Modifier and the
  Filter. Its 1.0 option no longer claims to be what simple iFlow steps use; that had no source.
- The Groovy 2.x engine bundles Camel's `camel-bean`, which Camel 3 needs for OGNL calls in Camel
  Simple.

### Fixed

- Opening a flow step right after a Groovy step no longer logs an uncaught `Canceled` error from the
  editor in the browser console.

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

[Unreleased]: https://github.com/jsevenheck/cpi-workbench/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.1.0
