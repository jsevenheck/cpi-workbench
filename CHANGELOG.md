# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/). A release takes its notes from the section
of its version.

## [Unreleased]

### Added

- **Camel Simple** tab: evaluates a Router or Looping Process Call condition (Non-XML) or a Content
  Modifier expression against a body, headers and properties with the Groovy engine's Camel. CPI's
  `=`, `and`, `or` and `${property.x}` are accepted on both Camel lines, and so are OGNL calls such
  as `${in.header.name.split(',')[0]}` (the Groovy 2.x engine now bundles `camel-bean`).

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

[Unreleased]: https://github.com/jsevenheck/cpi-workbench/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.1.0
