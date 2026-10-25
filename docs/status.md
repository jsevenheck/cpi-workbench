# Implementation status (2026-10-25)

## How to read the numbers

* **Built** is the share of the planned scope of an area (see [plan](plan.md) §1, §5–§10) that is
  implemented and covered by automated tests on Linux. It is an estimate made by listing the
  planned sub-features and the open items below; it is not a measured metric.
* **Tenant-verified** is 0 % for every area: the [probes](../probes/README.md) are written but have
  not been run on a tenant yet. Until then, "like CPI" means "like SAP's documentation, SAP's
  published interfaces and Camel's code say".
* Items that cannot be built with Saxon-HE (EE-only features) count as out of scope, not as gaps.

Automated checks:

* Engine tests (`mvn -f engine/pom.xml verify`): core 8, XSLT/XSD/XPath 22 per Saxon version, Groovy 22 per script version.
* Demo test cases: 6.
* Browser end-to-end tests: `app/e2e/smoke.mjs` and `groovy.mjs`.
* CI runs the engine tests on Linux, Windows and macOS, the browser e2e tests on Linux and Windows, and the shell tests on all three before it builds the installers.

## By area

| Area | Built | Open items |
|---|---|---|
| XSLT Mapping step (both Saxon profiles, parameter binding, `hci:` functions, includes, `java:` calls, error lines) | ≈ 90 % | `java:` calls: JDK classes only (no classes from the iFlow's jars), Saxon-EE argument conversion only in simple cases. |
| XSD validation | ≈ 90 % | XML Schema 1.0 only (JDK validator). |
| XPath tester | ≈ 95 % | — |
| Groovy Script 1.x (Groovy 2.4.21, Camel 2.24.3, Script API v1) | ≈ 90 % | Overloads that take Camel's `Attachment` type. Camel 3.14 variant in case the fingerprint shows it (F30). |
| Groovy Script 2.x (Groovy 4.0.29, Camel 3.14.7, Script API v2) | ≈ 90 % | ProcessDirect raises `UnsupportedInSimulator`. Methods the runtime class may have beyond the interface. |
| MPL, console, API call log | ≈ 95 % | — |
| Platform API mocks | ≈ 85 % | Data Store (no public API). Number ranges count per run only. OAuth2 tokens are fixed values. Services outside `generic.api` other than Value Mapping. |
| Test cases, runner, CLI | ≈ 90 % | No test case from an exported MPL trace (format unknown, F18). Attachments and SOAP headers are edited in `case.json` only, not in the app. |
| Runtime comparison (9.9 ↔ 12, 1.x ↔ 2.x) | ≈ 95 % | — |
| Lint (1.x → 2.x) | ≈ 80 % | Covers the known breaks (F26, v1-only methods, imports). Others appear only as compile or runtime errors. |
| iFlow-zip import | ≈ 75 % | Groovy, XSLT and XML Validator steps only. Header-driven mappings are flagged, not resolved. The format is undocumented (best effort; checked against 24 SAP sample iFlows). |
| Desktop app and browser mode | ≈ 90 % | Not yet run on real Windows or macOS hardware (CI builds only). |
| Distribution | ≈ 40 % | No code signing, no auto-update. One all-in-one installer instead of a base app plus a separate Groovy pack (plan §5.3). |
| Large messages | ≈ 70 % | Messages go through JSON-RPC, not temp files (plan §5.3). 7.4 MB worked (XSLT 1.3 s, Groovy 0.45 s); larger ones are untested. |
| Performance budgets | measured on Linux | Windows and macOS not measured. |
| Probe kit | written 100 %, run 0 % | Needs one run on a test tenant. |

## Small gaps closed in this round

* The Partner Directory, Keystore, Secure Store and `ITApiFactory` now follow SAP's published `generic.api` Javadoc:
  * Unknown keystore aliases return `null`.
  * Unsupported Partner Directory types throw `IllegalArgumentException`.
  * New: certificate parameters, authorized users, pattern lookups, key and trust managers, OAuth2 tokens, number ranges.
* Groovy test cases can carry input attachments. The app keeps a case's attachments, SOAP headers and platform overrides while the case is open (before, it dropped them).
* XSLT features that need Saxon-PE/EE now say that CPI supports them and which profile to use.
* Windows: script jars in `lib/` were locked while the Groovy engine ran (found by the new Windows
  CI tests). The engine now loads copies.
* XSD test cases open in the XSD view and can be saved from it.
* Corrected: earlier docs said XSLT `java:` calls could use the project's jars; they cannot.

## Next steps

1. Run the probes on a test tenant (Appendix A of the plan) and turn "to verify" into facts.
2. Run the installers on real Windows and macOS machines.
3. Code signing and auto-update (Phase 5).
