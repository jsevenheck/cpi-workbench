# Implementation status

This page describes the current checkout, including **Unreleased** changes. Released behaviour is
recorded in the [changelog](../CHANGELOG.md). Usage is in the [user guide](user-guide.md), detailed
limits in [Fidelity](fidelity.md), and research evidence and future work in the [plan](plan.md).

## Available

| Area | Implemented | Main limits |
|---|---|---|
| XSLT Mapping | Saxon-HE 9.9 and 12, typed parameter binding, `hci:` functions, project-local includes, JDK `java:` calls, error locations | Not Saxon-EE: no schema awareness or streaming; HE 9.9 lacks higher-order functions and dynamic evaluation. Java argument conversion is partial; project jars are not available to XSLT. |
| XSD and XPath | XML Schema 1.0 validation; XPath 3.1 (Saxon) and 1.0 (JDK), namespaces, result locations, XPath at cursor and session history | CPI's XML Validator implementation is unverified (Q-V1). Entity references disable the UI's text-based node locations. |
| Groovy Script | 1.x on Groovy 2.4.21; 2.x on 4.0.29; both on Camel 3.14.7 and Java 21, UTC and `en_US`; clean-room Script APIs, diagnostics, MPL, console and API call log | No sandbox. SAP-internal classes and some extension APIs are unavailable. `ScriptLogger` output destination on CPI is unknown. |
| Message Mapping functions | Single-value and per-context calls, `MappingContext`, `Output`, function picker and context editor | No graphical `.mmap` execution. Single-value functions run once with the supplied arguments, not automatically once per queue value. Marker identity semantics remain an assumption (Q-M1). |
| Platform mocks | Secure Store, Value Mapping, Partner Directory, Keystore and number ranges; Groovy input tables, attachments and SOAP headers | Test data only. Fixed OAuth2 tokens, number ranges per run, no Data Store API. Keystore and advanced platform fields use files. |
| Flow Steps | Content Modifier, Filter, Router, General / Iterating Splitter, Gather, JSON ↔ XML and XML ↔ CSV converters, Encoder and Decoder (Base64, GZIP, ZIP), Message Digest; binary bodies as input and result; UI and MCP | Some rules are inferred from SAP examples. Encoder, Decoder and Message Digest are not probed on a tenant yet (Q-E1; `probes/encoders/` is prepared): they follow Camel's data formats (F64), and Base64 line wrapping, the ZIP entry's name and the digest's *None* are assumptions. A Groovy script cannot return a binary body. No MIME Multipart, no parallel splitter execution, Zip/Tar Gather, EDI or XML Modifier. No flow-step CLI command or test-case step. |
| Project workflow | File editing, close/save/save-as, runtime comparison, run history with comparison (XSLT and Groovy tabs, in memory), Groovy migration lint, test-case loading and the import of iFlows, Value Mappings and integration packages | The export formats are undocumented and imported best effort (a value repeated without a default maps to the first group, an assumption; artifact types other than iFlows, script collections, REST API providers, message mappings and value mappings are skipped); unsupported steps are not listed. Header-driven mappings are flagged, not resolved. Failed imports may leave files already written. |
| CPI Helper hand-over | Pasted links/JSON, browser-mode links, opt-in desktop receiver with confirmation and malformed-body warnings | Plugin format is undocumented. Browser URL and unpacking limits apply; large traced bodies may already be cut off upstream (Q-T3). Receiver imports never run code automatically. |
| Tests and CLI | File-based XSLT, Groovy and XSD cases, expected bodies/headers/properties (compared as XML, JSON or text; `expect.ignore` for XML nodes or JSON values that change per run), mapping-function arguments and results; JUnit XML report (`test --junit`); a script stops at breakpoints when its engine jar runs under JDWP (`docs/debugging.md`) | Cases are written by hand and run from the CLI; the app loads them but has no test runner. No MPL-export-to-case import. `expect.ignore` is XPath 1.0 for XML bodies and JSON Pointers for JSON bodies. The JUnit report follows Maven Surefire's layout and has not been tried with a CI reporter. Breakpoints were tried with jdb only, not in an IDE, and the app has no debugger. |
| MCP | Ten tools, project files, iFlow import, source-derived reference, `develop_step` prompt, run chaining by `runId` | Separate process from the UI. Same code-execution privileges as the engines; result handles are bounded and process-local. No test runner tool. |
| Distribution | All-in-one Windows x64 and macOS Apple Silicon installers, engine jars, manual update check | No code signing/notarization or automatic update installation. No current Linux installer or Intel Mac build. |

## Evidence and automated coverage

* **Tenant probes:** one tenant on 2026-11-09, with follow-up fingerprint results in
  `probes/results-tenant/2026-11-09/` (F48–F61). They establish the observed runtime, parameter binding,
  platform lookup errors, converter examples and Message Mapping calls—not universal tenant parity.
* **CPI Helper:** GroovyDebugX and xsltDebugX messages were imported on Windows on 2026-11-16;
  the ledger records pasted links and receiver transfer (Q-T2). The full plugin-click acceptance
  check, macOS and browser Local Network Access restrictions remain release checks.
* **Engine tests:** `mvn -f engine/pom.xml verify`, plus the cases in `testdata/regression-project`.
  These cover both Saxon and both Groovy profiles, platform APIs and project boundaries.
* **UI unit tests:** formatters, rows, paths, completions, expressions, mapping arguments, platform
  files, link parsing/unpacking limits, XML text locations and version comparison.
* **Browser e2e:** `app/e2e/smoke.mjs` and `groovy.mjs`, including the zipped iFlow fixture.
* **Shell tests:** engine supervision over stdio, MCP protocol/file/result handling and the local
  receiver's origin, size, concurrency and deadline checks.
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
3. Add code signing and automatic updates. Continue workflow items W4–W10 in [plan §10.5](plan.md#105-workflow-roadmap).
4. Re-measure performance on current runtimes and Windows/macOS. The old Linux measurements in
   [Architecture](architecture.md#measured-performance) are historical, not current budgets.

A 25 MB traced body has been transferred through the receiver; that does not establish that every
engine can process a message of that size or larger. Memory and execution limits still apply.
