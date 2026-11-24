# Implementation status

This page describes the current checkout, including **Unreleased** changes. Released behaviour is
recorded in the [changelog](../CHANGELOG.md). Usage is in the [user guide](user-guide.md), detailed
limits in [Fidelity](fidelity.md), and research evidence and future work in the [plan](plan.md).

## Available

| Area | Implemented | Main limits |
|---|---|---|
| XSLT Mapping | Saxon-HE 9.9 and 12, typed parameter binding, `hci:` functions, project-local includes, JDK `java:` calls, error locations | Not Saxon-EE: no schema awareness or streaming; HE 9.9 lacks higher-order functions and dynamic evaluation. Reflexive Java calls require an explicit Run (including when used in a module). Java argument conversion is partial; project jars are not available to XSLT. |
| XSD and XPath | XML Schema 1.0 validation; XPath 3.1 (Saxon) and 1.0 (JDK), namespaces, result locations, XPath at cursor and session history | CPI's XML Validator implementation is unverified (Q-V1). Entity references disable the UI's text-based node locations. |
| Groovy Script | 1.x on Groovy 2.4.21; 2.x on 4.0.29; both on Camel 3.14.7 and Java 21, UTC and `en_US`; clean-room Script APIs, diagnostics, MPL, console and API call log | No sandbox. SAP-internal classes and some extension APIs are unavailable. `ScriptLogger` output destination on CPI is unknown. |
| Message Mapping functions | Single-value and per-context calls, `MappingContext`, `Output`, function picker and context editor | No graphical `.mmap` execution. Single-value functions run once with the supplied arguments, not automatically once per queue value. Marker identity semantics remain an assumption (Q-M1). |
| Platform mocks | Secure Store, Value Mapping, Partner Directory, Keystore and number ranges; Groovy input tables, attachments and SOAP headers | Test data only. Fixed OAuth2 tokens, number ranges per run, no Data Store API. Keystore and advanced platform fields use files. |
| Flow Steps | Content Modifier, Filter, Router, General / Iterating Splitter, Gather, JSON ↔ XML and XML ↔ CSV converters, Encoder and Decoder (Base64, GZIP, ZIP), Message Digest; binary bodies as input and result; UI and MCP | Some rules are inferred from SAP examples. Encoder, Decoder and Message Digest are not probed on a tenant yet (Q-E1; `probes/encoders/` is prepared): they follow Camel's data formats (F64), and Base64 line wrapping, the ZIP entry's name and the digest's *None* are assumptions. A Groovy script's binary result is not preserved as bytes: it is shown as text with an `UnsupportedInSimulator` warning. No MIME Multipart, no parallel splitter execution, Zip/Tar Gather, EDI or XML Modifier. No flow-step CLI command. |
| Fidelity indicator | Per step and runtime the weakest basis (tenant, documented, assumption) of its rows in `docs/fidelity.md`, read when the app is built; a click lists the rows | Shows the page as of the build; a row's basis is the weakest claim in it, so a row that is mostly tenant-verified can show *Assumptions*. |
| Project workflow | File editing, close/save/save-as, runtime comparison, run history with comparison (XSLT and Groovy tabs, in memory), Groovy migration lint, the import of iFlows, Value Mappings and integration packages, an imported iFlow's outline and diagram (read-only), an offline where-used overview (what the iFlows use, with `{{parameters}}` resolved) and search across the imported iFlows, and starting a new project from an export (**Import iFlow…** without a project) | The export formats are undocumented and imported best effort (a value repeated without a default maps to the first group, an assumption; artifact types other than iFlows, script collections, REST API providers, message mappings and value mappings are skipped); unsupported elements appear in the outline/diagram but cannot run. Header-driven mappings are flagged, not resolved. Where-used kinds (address, credential, queue, key alias) come from adapter keys seen in public exports; other keys are matched as plain values. Values are matched as exported, with `{{parameters}}` unresolved. Package folder-name collisions (after sanitization, ignoring case and trailing dots) and folders that differ from an existing one only in case or trailing dots are rejected before any writes, including value mappings, in both create-new and overwrite modes. Archives are staged before publishing; the expanded import is limited to 200 MiB and 10,000 entries. The Value Mapping table is replaced atomically. Filesystem errors while publishing can still leave committed or partial folders, which the importer names for review before retrying; a failed create-new import attempts to clean up only the entries it created, without removing other files in the destination. Archives with duplicate entries (including case-only duplicates on case-insensitive file systems) are refused. Overwrite imports keep files the new export no longer contains and list them in the result (`kept`); they still appear in Steps and Where used until removed. An alias folder linked inside the project shows in the file tree under both names; iFlow readers use each model once. |
| CPI Helper hand-over | Pasted links/JSON, browser-mode links, opt-in desktop receiver with confirmation and malformed-body warnings | Plugin format is undocumented. Browser URL and unpacking limits apply; large traced bodies may already be cut off upstream (Q-T3). Receiver imports never run code automatically. |
| CLI and debugging | The engine jars run a mapping, script, schema or XPath from a terminal; a script stops at breakpoints when its engine jar runs under JDWP (`docs/debugging.md`) | Breakpoints were tried with jdb only, not in an IDE, and the app has no debugger. |
| Regression tests (internal) | `case.json` cases in `testdata/regression-project/tests` run on all four engines in CI ([format](test-cases.md)): expected bodies/headers/properties, `expect.ignore`, mapping-function arguments, JUnit XML (`test --junit`) | Not a user feature (2026-11-17). The JUnit report follows Maven Surefire's layout and has not been tried with a CI reporter. |
| MCP | Eleven tools, project files, iFlow import, where-used search, source-derived reference, `develop_step` prompt, run chaining by `runId` | Separate process from the UI. Same code-execution privileges as the engines; result handles are bounded and process-local. A run over the 64 MiB run cache gets no `runId`; a result over the 1 MiB preview has its longest lists cut. |
| Distribution | All-in-one Windows x64 and macOS Apple Silicon installers, engine jars, manual update check | No code signing/notarization or automatic update installation. No current Linux installer or Intel Mac build. |

## Evidence and automated coverage

* **Tenant probes:** one tenant on 2026-11-09, with follow-up fingerprint results in
  `probes/results-tenant/2026-11-09/` (F48–F61). They establish the observed runtime, parameter binding,
  platform lookup errors, converter examples and Message Mapping calls—not universal tenant parity.
* **CPI Helper:** GroovyDebugX and xsltDebugX messages were imported on Windows on 2026-11-16;
  the ledger records pasted links and receiver transfer (Q-T2). The full plugin-click acceptance
  check, macOS and browser Local Network Access restrictions remain release checks.
* **Engine tests:** `mvn -f engine/pom.xml verify`, plus the cases in `testdata/regression-project`.
  These cover both Saxon and both Groovy profiles, platform APIs and project boundaries. HTTP transport
  tests cover declared and chunked body limits, stalled uploads, admission overload, and an expired
  queue wait alongside a successful active call. XSLT preview tests cover `java:` namespaces hidden by
  character or entity references or EQNames; Content Modifier tests cover a binary body read as text
  that is not valid UTF-8 (a warning). Project containment fixtures exercise Init and iFlow
  model, mapping and parameter reads with POSIX symlinks or Windows directory junctions. Tests of file
  symlinks are skipped on Windows if the user lacks the necessary privilege. Package staging tests
  check aggregate archive limits and a clean retry after a damaged later artifact.
* **UI unit tests:** formatters, rows, paths, completions, expressions, mapping arguments, platform
  files, link parsing/unpacking limits, XML text locations, run history, iFlow model ordering/layout,
  browser engine URL validation, version comparison, engine error kinds (create-new conflict, unavailable
  project folder), the engines a Flow Steps configuration needs and the message naming a missing one, and
  which `function:simple(...)` calls a Filter XPath contains (not those in its literals or comments).
* **Browser e2e:** `app/e2e/smoke.mjs` and `groovy.mjs`, including the zipped iFlow fixture and
  delayed-save checks, a two-tab check for project-local XSLT resources and saves, and overwrite
  checks for destinations that are unlisted or created concurrently and for binary output. The Groovy
  journey also checks that a Content Modifier's exchange ID stays the same across an XPath row.
  The smoke test checks Tab, Shift+Tab, Escape and focus return in the shared import dialog, Cancel
  focused in the discard and overwrite questions, focus after a context-menu Rename, Save as… onto the
  open file without an overwrite question, and an
  Encoder run and missing-Camel diagnostic in an XML-only deployment. Shared parity fixtures and
  smoke tests cover a Filter substitution that returns text resembling another Simple call, and
  call-like text in the XPath's own string literals and comments.
  Browser and MCP smoke tests chain GZIP Encoder → header-only Content Modifier → Decoder,
  checking the exact bytes and the headers passed on.
* **Shell tests:** engine supervision over stdio, including the first desktop Groovy run after an idle
  stop or abrupt worker exit, no-ready and blocked-pipe fixtures for deadlines and shutdown, a failed
  start call, the frame limit on requests and responses, and MCP
  protocol/file/result handling (including slow output, overload, immediate id reuse and
  oversized runs and results), and the local receiver's origin, size,
  concurrency and deadline checks.
* **CI:** engines on Linux, Windows and macOS; browser e2e on Windows; shell tests, native builds,
  installers and release-binary MCP smoke tests on Windows and macOS. Commands: [Building](building.md).

Unit-test counts are intentionally not duplicated here; the test runner and CI report them.
The receiver's store/event coordination and browser XML warning paths do not yet have permanent
regression coverage. Node tests do not exercise `DOMParser`; check Edge and Firefox separately.

## Open verification and next work

1. Complete the [manual release checks](manual-test.md), particularly macOS installation/WKWebView,
   update-page opening, the receiver's full CPI Helper click path and MCP file workflows in a real client.
   Windows NSIS installers have been tested manually; a managed laptop refused the unsigned MSI.
2. Close the remaining fidelity questions: CSV byte-level line ends, XML Validator, `ScriptLogger`
   destination, mapping markers and non-XML Gather inputs ([Appendix A](plan.md#appendix-a--question-checklist)).
3. Add permanent receiver-coordination and browser XML-warning regression tests.
   Code signing and automatic update installation remain open ([plan §10.2](plan.md#102-updates));
   Encoder / Decoder / Message Digest verification uses the prepared tenant probe (W4, Q-E1).
4. Re-measure performance on current runtimes and Windows/macOS. The old Linux measurements in
   [Architecture](architecture.md#measured-performance) are historical, not current budgets.

A 25 MB traced body has been transferred through the receiver; that does not establish that every
engine can process a message of that size or larger. Memory and execution limits still apply.
