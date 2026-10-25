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

* Engine tests (`mvn -f engine/pom.xml verify`): core 11, XSLT/XSD/XPath 33 per Saxon version (11 of them on project boundaries), Groovy 23 per script version.
* Regression test cases (`testdata/regression-project`): 6.
* Browser end-to-end tests: `app/e2e/smoke.mjs` and `groovy.mjs`.
* UI unit tests (`npm --prefix app test`): the JSON, XSLT and Groovy formatters, test-case rows.
* CI runs the engine tests on Linux, Windows and macOS, the browser e2e tests on Linux and Windows, and the shell tests on all three before it builds the installers. All of them passed on Windows and macOS runners for the first time on 2026-10-25 (`d237e25`).

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
| Test cases, runner, CLI | ≈ 90 % | No test case from an exported MPL trace (format unknown, F18). Cases are written by hand and run from the CLI; the app only opens them (the Tests tab and *Save as test* were removed on 2026-10-25 because they were not needed). |
| Runtime comparison (9.9 ↔ 12, 1.x ↔ 2.x) | ≈ 95 % | — |
| Lint (1.x → 2.x) | ≈ 80 % | Covers the known breaks (F26, v1-only methods, imports). Others appear only as compile or runtime errors. On the 1.x runtime, the compile error for a Script API v2 or `groovy.xml.XmlSlurper` import says that it needs 2.x. |
| iFlow-zip import | ≈ 75 % | Groovy, XSLT and XML Validator steps only. Header-driven mappings are flagged, not resolved. The format is undocumented (best effort; checked against 24 SAP sample iFlows). |
| Desktop app and browser mode | ≈ 90 % | Engines, shell and browser mode are tested on Windows and macOS in CI. On Windows, the desktop window (WebView2, `tauri dev`) was tried by hand on 2026-10-25 with XSLT, Groovy 1.x/2.x, XSD validation and iFlow import. Not yet tried by hand: the installers, macOS (WKWebView). |
| Distribution | ≈ 40 % | No code signing, no auto-update. On a managed Windows laptop, the unsigned MSI is refused as untrusted, so it could not be tried there. One all-in-one installer instead of a base app plus a separate Groovy pack (plan §5.3). |
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
* The browser e2e tests waited for a stale result badge and could read the previous case's output
  (seen on Windows); they now wait for the expected content.
* XSD test cases open in the XSD view.
* Corrected: earlier docs said XSLT `java:` calls could use the project's jars; they cannot.

From the first manual test of the desktop window on Windows:

* Panes can be resized by dragging; inputs can be saved to and loaded from `inputs/`. Other files
  (platform data, configuration) open in a File tab instead of replacing the input body.
  With *Live* on, opening a mapping or script or loading an input runs the step again.
* File tree context menu (open, rename, copy, delete, use as input), **Save as…** for mappings,
  scripts and schemas, and **Format** for mappings and scripts that only moves insignificant
  whitespace.
* JSON input bodies for Groovy scripts: highlighted, formatted without changing numbers, and always
  loaded into the Groovy tab.
* On the 1.x runtime, a Script API v2 or `groovy.xml` import explains that the script needs 2.x.
* The Tests tab and *Save as test* were removed; test cases stay a CLI feature.
* Local builds without GraalVM: `cargo test` and `tauri dev` work with a config override
  ([building](building.md#without-graalvm)).

From a code review (October 2026):

* Unsaved changes: opening another file, a test case or an iFlow step asks first; new files and
  saves ask before they overwrite an existing file (*+ Script* used to overwrite `new-script.groovy`).
* Opening a script resets the function to `processData`; an iFlow step's function and runtime are
  set before the first live run.
* Closing the project closes it in the engines too (`project.close`); before, a run could still
  read the old project's `platform/` data.
* XML runs: external entities, DTDs, `doc()` and `unparsed-text()` (XPath and XSLT) now stay inside
  the project, as the docs said. `xsl:result-document` could write to any path; it is now
  `UnsupportedInSimulator`, like `collection()`.
* Browser mode: the CORS check compared origin prefixes, so `http://tauri.localhost.example` passed;
  it now compares hosts exactly.
* iFlow import: the names `.` and `..` are refused, and the size limit stops the copy as soon as it
  is exceeded.
* Test cases: a JSON boolean header or property stays a Boolean in the app, as on the CLI.
* `cpi-workbench.json` no longer gets the unused `defaultXsltProfile` setting.
* Native XML engines: parser error messages no longer fail on missing resource bundles.

## Next steps

1. Run the probes on a test tenant (Appendix A of the plan) and turn "to verify" into facts.
2. Install and open the installers on Windows (a machine without a policy against unsigned MSIs) and macOS once by hand.
3. Code signing and auto-update (Phase 5).
