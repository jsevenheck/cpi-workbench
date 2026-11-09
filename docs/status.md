# Implementation status (2026-11-09)

## How to read the numbers

* **Built** is the share of the planned scope of an area (see [plan](plan.md) §1, §5–§10) that is
  implemented and covered by automated tests on Linux. It is an estimate made by listing the
  planned sub-features and the open items below; it is not a measured metric.
* **Tenant-verified:** on 2026-11-09 all [probes](../probes/README.md) ran on one tenant: fingerprints,
  XSLT, platform, the four converters and the Message Mapping probe (F48–F61, `probes/results-tenant/`).
  Elsewhere "like CPI" means "like SAP's documentation, SAP's published interfaces and
  Camel's code say".
* Items that cannot be built with Saxon-HE (EE-only features) count as out of scope, not as gaps.

Automated checks:

* Engine tests (`mvn -f engine/pom.xml verify`): core 13, XSLT/XSD/XPath/Splitter/Filter/Gather/Converter 74 per Saxon version (16 of them on project boundaries, 8 splitter cases from SAP's examples, 5 Filter, 3 Gather and 18 converter tests, most with SAP's examples), Groovy 44 per script version (4 Camel Simple, 4 Content Modifier, 11 Message Mapping functions, 2 on what a tenant probe showed).
* Regression test cases (`testdata/regression-project`): 9.
* Browser end-to-end tests: `app/e2e/smoke.mjs` (XML tools) and `groovy.mjs` (Groovy, Flow Steps and the iFlow import, which CI runs with the iFlow fixture as a zip).
* UI unit tests (`npm --prefix app test`): the JSON, XSLT and Groovy formatters, test-case rows, path comparison, the Script API completions (checked against the clean-room Java classes), the Filter's `function:simple(...)` substitution, Message Mapping arguments and the update check's SemVer comparison.
* CI runs the engine tests on Linux, Windows and macOS, the browser e2e tests on Linux and Windows, and the shell tests on all three before it builds the installers. All of them passed on Windows and macOS runners for the first time on 2026-10-25 (`d237e25`). macOS runs on `macos-26` (GitHub deprecated `macos-14`); the native engines are built for macOS 11 and CI checks that.

## By area

| Area | Built | Open items |
|---|---|---|
| XSLT Mapping step (both Saxon profiles, parameter binding, `hci:` functions, includes, `java:` calls, error lines) | ≈ 90 % | `java:` calls: JDK classes only (no classes from the iFlow's jars), Saxon-EE argument conversion only in simple cases. |
| XSD validation | ≈ 90 % | XML Schema 1.0 only (JDK validator). |
| XPath tester | ≈ 95 % | — |
| Flow Steps: Content Modifier, Filter, Router, General / Iterating Splitter, Gather (with Camel Simple), Converter | ≈ 80 % | `=`, `and`, `or` are rewritten to Camel's spelling on an assumption (F35). Splitter rules are inferred from SAP's examples. Gather's Combine at XPath has no SAP example (an assumption); Zip and Tar are not simulated. Number Range and External Parameter sources raise `UnsupportedInSimulator`; variables use their default. Converters (JSON ↔ XML, XML ↔ CSV) follow SAP's rules and examples; output formatting, CSV quoting and line ends, and one contradiction in SAP's JSON to XML docs are open (Q-C1). Not simulated: EDI, Encoder/Decoder, XML Modifier, Message Digest, MIME Multipart. No CLI command, no test-case step. |
| Groovy Script 1.x (Groovy 2.4.21, Camel 3.14.7, Java 21, Script API v1) | ≈ 90 % | Matches a tenant's fingerprint (F48–F50, F53, F58), library versions included, except: where `log.logInfo(…)` writes on a tenant is not known (the simulator prints to the console), and `getLogger` / `getCategoryLogger` are not simulated; IAIK and Camel 2's `org.apache.camel.Attachment` are not bundled; `getExtensionFunctions` (type not published). |
| Groovy Script 2.x (Groovy 4.0.29, Camel 3.14.7, Java 21, Script API v2) | ≈ 90 % | ProcessDirect raises `UnsupportedInSimulator`. Not simulated: `setProxyHandler` and the SAP classes `Exchange` / `SimpleBuilder` a tenant's scripts can load (behaviour not published). |
| Message Mapping functions (`MappingContext`, `Output`) | ≈ 80 % | The function runs on its own; `.mmap` is out of scope. A tenant confirmed Groovy 2.4.21 / Java 21, one call per context for a `String[]` function joined by context changes, and the marker texts (F59, F60). A tenant also calls a `String[]` function for an empty context (F61), as the simulator does. A single-value function runs once with the values given (a tenant calls it per value); identity vs. equals for markers is open (Q-M1). |
| MPL, console, API call log | ≈ 95 % | — |
| Platform API mocks | ≈ 85 % | Messages and results for unknown names match a tenant (F52). Data Store (no public API). Number ranges count per run only. OAuth2 tokens are fixed values. Services outside `generic.api` other than Value Mapping. |
| Test cases, runner, CLI | ≈ 90 % | No test case from an exported MPL trace (format unknown, F18). Cases are written by hand and run from the CLI; the app only opens them. |
| Runtime comparison (9.9 ↔ 12, 1.x ↔ 2.x) | ≈ 95 % | — |
| Lint (1.x → 2.x) | ≈ 80 % | Covers the known breaks (F26, v1-only methods, imports). Others appear only as compile or runtime errors. On the 1.x runtime, the compile error for a Script API v2 or `groovy.xml.XmlSlurper` import says that it needs 2.x. |
| iFlow-zip import | ≈ 80 % | Groovy, XSLT, XML Validator, Content Modifier, Filter, Router, General / Iterating Splitter, Gather and converter steps (`{{parameters}}` from `parameters.prop`); Message Mapping steps with the scripts their `.mmap` uses (F45; the mapping is not simulated); other steps are not listed. Header-driven mappings are flagged, not resolved. The format is undocumented (best effort; checked against 389 exported iFlows from SAP's samples and 50 public iFlows with converters, F36, F38, F39, F46). A failed import leaves the files it already wrote. |
| MCP server (`cpi-workbench mcp`) | ≈ 90 % | One tool per tab of the app (XSLT, Groovy, XSD, XPath, Flow Steps) plus the project view; the app leads, the server adds no function. Tested with the official TypeScript and Python SDK clients (2025-11-25) and the repository's own tests (2026-07-28); by hand with Claude Code and Claude Desktop on the demo project for the 0.2.0 release ([Manual test](manual-test.md), section 5). Groovy runs without a sandbox (docs/mcp.md). |
| Desktop app and browser mode | ≈ 90 % | Engines, shell and browser mode are tested on Windows and macOS in CI. On Windows, the `setup.exe` of 0.2.0 passed the [manual test](manual-test.md) on 2026-11-06. Not yet tried by hand: the `.msi` outside a managed laptop, macOS (WKWebView). |
| Distribution | ≈ 45 % | *Check for updates* (0.3.0) links to a newer release; the release page opens through the shell's own command, not yet tried in the desktop window on Windows or macOS. No code signing, no auto-update. On a managed Windows laptop, the unsigned MSI is refused as untrusted, so it could not be tried there. One all-in-one installer instead of a base app plus a separate Groovy pack (plan §5.3). |
| Large messages | ≈ 70 % | Messages go through JSON-RPC, not temp files (plan §5.3). 7.4 MB worked (XSLT 1.3 s, Groovy 0.45 s); larger ones are untested. |
| Performance budgets | measured on Linux | Windows and macOS not measured. |
| Probe kit | run on one tenant: fingerprints (twice), XSLT, platform, converters, Message Mapping | The Message Mapping probe ran with both targets and both requests (F59–F61). The converters matched (F54–F57); `probes/mapping/` and `probes/converters/` hold their files. `logMethods` ran too (F58). |

## Next steps

1. Manual test of 0.3.0 on Windows ([Manual test](manual-test.md)), then the release.
2. Run the Message Mapping probe.
3. Install and open the installer on macOS once by hand.
4. Code signing and auto-update (Phase 5, plan §10.2).

Fixes from reviews and earlier rounds are listed in the [changelog](../CHANGELOG.md).
