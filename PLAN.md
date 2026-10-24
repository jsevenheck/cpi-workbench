# Integration Suite Local Workbench — Project Plan

Status: **Draft v2 for review**. Nothing is implemented yet. Please review the open questions in §12 before work starts.

Working name: **CPI Workbench** (placeholder; rename freely).

Changes in v2: native (non-Java) XSLT/XSD engines researched and adopted (§2, §3, §5); Rust/Tauri confirmed as the shell, Java confined to Groovy only; a research method based on REA's evidence rules added (§2.1, Appendix A).

---

## 1. Goal

A small, fast, **fully offline** desktop app for Windows and macOS (Linux as a free side effect) to develop and test the two kinds of mapping logic most often used in SAP Integration Suite / Cloud Integration (CPI):

1. **Groovy Scripts** — both script step generations:
   - **Script API v1** (Groovy Script step 1.x)
   - **Script API v2** (Groovy Script step 2.x)
2. **XSLT Mappings** — XSLT 1.0/2.0/3.0 as executed by the CPI XSLT Mapping step (version 1.2+ supports XSLT 3.0).

Plus supporting XML tooling: **XSD validation**, XPath evaluation, and later small helpers.

The app simulates the CPI runtime contract (message body, headers, exchange properties, attachments, logging, selected platform APIs) closely enough that a script or stylesheet that works locally behaves the same once deployed. It is a **simulation**, not the SAP runtime — §9 lists where it can and cannot match.

### Non-goals (for now)
- Running whole iFlows, adapters, routers, or Message Mappings (graphical mappings).
- Connecting to a tenant (deployment, downloading artifacts). This could come later as an opt-in; the core stays offline.
- Shipping SAP proprietary code or jars, or decompiling SAP runtime binaries.

---

## 2. Research findings

### 2.1 How findings are labelled
This follows the evidence rules of the *Reverse Engineer Anything* (REA) workflow skill (`morluto/rea`). REA itself (MCP server + decompilers) is not installed in this session, and its tooling targets shipped binaries, so it does not apply directly. Its **method** does apply:

- every finding is tagged **Observed** (read in a primary source or measured), **Inferred** (concluded from observations), or **Unknown**;
- each finding links to its evidence (Sources, numbered `[S#]`);
- the research questions are kept as a checklist and each is marked answered / partial / open (Appendix A);
- observed CPI behaviour and our own design choices are kept apart;
- the "target" here is the CPI runtime, which we can only observe as a black box through test iFlows (§8.4), not by decompiling it.

### 2.2 Finding ledger

| # | Finding | Label | Evidence |
|---|---|---|---|
| F1 | Groovy Script step 1.x runs **Groovy 2.4.21** | Observed | [S1] |
| F2 | Groovy Script step 2.x runs **Groovy 4.0.29** | Observed | [S1] |
| F3 | A diagnostic script on a 2.x step reported **Java 17.0.18, Groovy 4.0.29, Camel 3.14.7** | Observed (single community report) | [S2] |
| F4 | The Java version that runs **1.x** scripts today | **Unknown** — SAP Help still mentions Java 8 libraries, may be outdated | [S1] |
| F5 | v1 API: `com.sap.gateway.ip.core.customdev.util.Message`, entry `def Message processData(Message message)` | Observed | [S1][S5] |
| F6 | v2 API lives in `com.sap.it.script.v2.api` (`Message`, `Exchange`); full method list | Package Observed; method list **Unknown** | [S2][S3] |
| F7 | SAP Script API jar is downloadable from SAP Development Tools, is "bare-bones", some versions lack `Message`, and is under an SAP licence | Observed (community) → we must not bundle it (Inferred) | [S5][S6] |
| F8 | CPI's XSLT engine is **Saxon-EE**; reported versions 9.9.1.6 (Nov 2025) and 9.8.0.12 (older) | Observed (community, conflicting versions) | [S8][S9] |
| F9 | XSLT 3.0 is supported since XSLT Mapping step 1.2 (April 2019) | Observed | [S7] |
| F10 | **go-xslt** (Tim Riep): pure-Go XSLT 3.0 / XPath 3.1 / XSD 1.0+1.1 engine, no CGo, no JRE; Apache-2.0; ships a desktop XSLT tester; self-reported W3C conformance 99.9 % XSLT 3.0 (8,635/8,647), 99.7 % XPath/F&O 3.1, 99.99 % XSD 1.0 and 1.1, with streaming and schema-awareness; v0.3.0, "before 1.0", 3 stars, issues accepted but **no pull requests** | Observed (README) | [S10] |
| F11 | That go-xslt is the tool from your LinkedIn post | **Inferred** — it matches "XSLT tester in Go without Java", but I did not see the post | [S10] |
| F12 | Whether go-xslt supports **custom extension functions** (needed for `cpi:setHeader`) | **Unknown** — not mentioned in its README | [S10] |
| F13 | Other Go engines: `lestrrat-go/helium/xslt3` (targets *Basic* XSLT 3.0 conformance, v0.8.0), `speedata/goxslt` (early stage), `go-xslt/xslt` (XSLT 1.0 only) | Observed | [S11][S12][S13] |
| F14 | **SaxonC-HE**: Saxon compiled to native code with GraalVM Native Image — runs **without a JVM**, C/C++/Python/PHP APIs, open source (MPL). HE lacks schema awareness and streaming. Latest is 12.x or 13.0 (Saxonica pages conflict). No Rust bindings found. | Observed; Rust FFI feasibility **Inferred** | [S14][S15] |
| F15 | Rust XSLT: `xrust` reached XSLT 1.0 / XPath 1.0 functional parity only; `xee` status not found | Observed / Unknown | [S16] |
| F16 | **No non-JVM Groovy implementation** exists (only a 2004 experiment and Groovy→JS transpiler grooscript for Groovy 2) | Observed (absence in searches) | [S17] |
| F17 | Dynamic Groovy (`GroovyShell`, runtime script compilation) **does not work in a GraalVM native image**: native images cannot load new classes at runtime; Groovy relies heavily on reflection. Camel Quarkus only manages static, build-time compilation. | Observed | [S18][S19] |
| F18 | Web IDEs for CPI Groovy exist (Contiva Groovy IDE, GrooveBox); the usual local approach is a hand-made `MessageImpl` stub | Observed (snippets only; tools not inspected) | [S4][S20][S21] |
| F19 | ArtCraft apps are pure Rust with egui/wgpu, no webview | Observed (single third-party page) | [S22] |

### 2.3 What this means
- **XSLT and XSD can be done fully without Java.** (F10, F14) — you were right.
- **Groovy cannot.** A CPI Groovy script is compiled at runtime and calls Groovy and JDK classes (`XmlSlurper`, `JsonSlurper`, `java.util.*`, Camel type converters). Faithful execution needs the real Groovy 2.4.21 / 4.0.29 on a real JVM (F16, F17). Rewriting Groovy in Rust or Go would mean rewriting Groovy *and* the Java class library — not feasible, and never faithful.
- Fidelity for XSLT is not only about XSLT 3.0 conformance. CPI runs **Saxon**, so implementation-defined behaviour (serialization defaults, `format-number`/`format-dateTime` output, regex edge cases, error codes and messages, key/collation details) follows Saxon. An independent engine like go-xslt can be 99.9 % conformant and still differ in such edge cases. That is why the plan uses **two engines and compares them** (§5.2).

---

## 3. Technology decision

### 3.1 Is Rust a good fit?
Yes, **for the app itself**: small binaries, fast startup, safe process supervision, file handling, packaging and auto-update via **Tauri 2**. It is not a fit for the *engines*, because none exists in Rust at the required level (F15) and Groovy cannot exist outside the JVM (F16, F17). So Rust is the shell that calls the best engine available for each job.

Why Tauri and not egui (the ArtCraft stack, F19): this app is mostly code editors, tables and diff views. Tauri's webview gives us the **Monaco** editor (the VS Code editor: syntax highlighting, error markers, diff view) for free; egui has no comparable code editor.

Alternative considered: **Go + Wails**, which would embed go-xslt in-process. It is a sound option, but Rust/Tauri can use go-xslt equally well as a sidecar binary and can also link SaxonC through its C API, which Go could only do via CGo. Rust/Tauri stays the recommendation; I'm happy to switch to Go if you prefer it.

### 3.2 Architecture

```
┌──────────────────────────── Tauri 2 app (Rust) ───────────────────────────┐
│  Web UI (TypeScript + Svelte, Monaco editor)                               │
│        │ Tauri commands / events                                           │
│  Rust core: projects, settings, process supervisor, test runner, CLI       │
│        │                                │                                  │
│        │ JSON-RPC over stdio            │ C FFI (in-process) or sidecar    │
└────────┼────────────────────────────────┼──────────────────────────────────┘
         ▼                                ▼
  Groovy workers (JVM, only for Groovy)   XML engines (native, no Java)
  ┌───────────────────────────────┐      ┌────────────────────────────────┐
  │ worker-groovy-v1              │      │ go-xslt  (sidecar binary)      │
  │   Groovy 2.4.21 + v1 API shim │      │ SaxonC-HE (native lib via FFI) │
  │ worker-groovy-v2              │      │ optional: your own Saxon-EE    │
  │   Groovy 4.0.29 + v2 API shim │      └────────────────────────────────┘
  │ bundled minimal JRE (jlink)   │
  └───────────────────────────────┘
```

Key properties:
- **No Java for XSLT/XSD/XPath.** The JVM starts only when you open a Groovy test, and only then.
- The JRE is bundled and hidden: no Java installation needed, no Java UI. A `jlink`-trimmed JRE is roughly 45–70 MB (estimate, to be measured in the spike); the "fetch on first use" option in §8.2 keeps it out of the base installer.
- Groovy v1 and v2 run in **separate worker processes**: no leakage between Groovy 2 and 4; v1 can use a different Java version if calibration shows that (F4); a runaway script (endless loop, `System.exit`, OOM) kills only its worker.
- Workers stay warm, so runs after the first one take milliseconds.

---

## 4. Groovy Script Simulator

### 4.1 Input panel (mirrors the CPI exchange)
- **Body**: text editor with mode switch (XML / JSON / plain / binary from file). Encoding selector (UTF-8 default). Pretty-print buttons.
- **Headers** table: name, value, **Java type** (`String`, `Integer`, `Long`, `Boolean`, `byte[]`, `Date`, `null`, …). CPI scripts often break on types, so types are first-class.
- **Exchange properties** table: same as headers.
- **Attachments**: name, content type, file.
- **Script**: Monaco editor; **function name** field (default `processData`, as in the CPI step configuration).
- **Script collection / libraries**: add other `.groovy` files and `.jar` files to the classpath, like resources/script collections referenced by an iFlow.
- **Runtime profile**: `Groovy Script 1.x` or `Groovy Script 2.x` (§8).

### 4.2 Output panel
- Resulting body (with **diff against input**), headers and properties (added / changed / removed highlighted, including type changes).
- **MPL view**: what the script wrote via `messageLogFactory.getMessageLog(message)` — custom header properties, string properties, log attachments (`addAttachmentAsString`).
- Console (`println`, `System.out/err`), logger output (SLF4J).
- Exception view: the exception as CPI would surface it, with the stack trace filtered to script line numbers.
- Timing and memory used.

### 4.3 Simulated API surface (clean-room)
Implemented by us, matching the public package and class names so scripts compile unchanged. Signatures come from public SAP documentation and, if you provide it, the public Script API jar on your machine (§12, Q4) — never from decompiled SAP runtime code.

**v1** (`com.sap.gateway.ip.core.customdev.util.Message`):
- `getBody()`, `getBody(Class)`, `setBody(Object)`
- `getHeaders()`, `getHeader(String, Class)`, `setHeader`, `setHeaders`
- `getProperties()`, `getProperty`, `setProperty`, `setProperties`
- `getAttachments()`, `setAttachments`, `addAttachmentObject`, …
- `getBodySize()`, `getAttachmentsSize()`
- `messageLogFactory` binding → `MessageLog` with `setStringProperty`, `addCustomHeaderProperty`, `addAttachmentAsString`

**v2** (`com.sap.it.script.v2.api.*`): Message/Exchange API as documented by SAP for script step 2.x. The method list is still Unknown (F6) and must be confirmed before implementation.

**Type conversion fidelity:** in CPI, `getBody(String)` / `getHeader(name, Type)` go through **Apache Camel's type converters**. Instead of re-implementing those rules, the shim wraps a real Apache Camel `DefaultExchange` (Camel 3.14.x for v2 per F3; the v1 Camel version is to be calibrated). This gives realistic conversions for `InputStream`, `byte[]`, `String`, `Reader`, `org.w3c.dom.Document`, etc. — a frequent source of "works locally, fails on CPI" bugs.

**Platform APIs** (`com.sap.it.api.ITApiFactory`): mocked and driven by a local, per-project JSON file:
- `SecureStoreService` → user credentials (local fake values; never real secrets in project files by default)
- `ValueMappingApi` → value mapping tables (import from CSV)
- `KeystoreService` → local test keystore
- `PartnerDirectoryService`, `DataStore`, number ranges → later phases

Anything not simulated throws a clear `UnsupportedInSimulator` error naming the API, instead of failing silently.

### 4.4 Compatibility linting (static checks before running)
- Flags v1 → v2 migration issues: v1 `Message` imports in a v2 profile, Groovy 2 → 4 breaking changes (e.g. `groovy.util.XmlSlurper` moved to `groovy.xml.XmlSlurper`), wildcard imports (SAP's upgrade guidance discourages them, [S3]).
- Flags Java APIs not available on the profile's Java version.
- "Run under both profiles" button to compare results side by side — useful while upgrading scripts to 2.x.

---

## 5. XSLT Mapping Simulator (no Java)

### 5.1 Behaviour to mimic from the CPI XSLT Mapping step
- Input = message body (whether a header/property can be the source depends on the step version — **to verify**).
- **Headers and exchange properties are available as `xsl:param`** by name.
- Setting headers/properties from XSLT via the `$exchange` parameter and SAP's extension functions (`cpi:setHeader`, `cpi:setProperty`, namespace `http://sap.com/it/`). **Exact signatures to verify against SAP Help.**
- Output as body; header/property changes shown as in the Groovy simulator.
- `xsl:import` / `xsl:include` resolved from the project folder.

**Design choice — engine-independent `cpi:` emulation.** Because extension-function support differs per engine (F12, F14), the app wraps your stylesheet in a generated driver stylesheet that `xsl:import`s it and defines `cpi:setHeader` / `cpi:setProperty` as `xsl:function`s in the `http://sap.com/it/` namespace. Each call emits a structured `xsl:message` that the app collects into the resulting headers/properties. This works on any XSLT 3.0 engine and needs no engine plugin. Risk to check in the spike: lazy evaluation means a call whose result is never used may not run. In CPI the usual pattern uses the result (`<xsl:value-of select="cpi:setHeader(...)"/>`), so this should match; the spike will test that pattern on both engines.

### 5.2 Engines (pluggable)

| Engine | Java? | Why | Limitations |
|---|---|---|---|
| **SaxonC-HE** (default for fidelity) | No (GraalVM native lib) | Same Saxon code base as CPI → same implementation-defined behaviour | Version 12/13 vs. CPI's reported 9.9 (F8); no schema awareness/streaming (EE features); integration via C FFI is untested (F14) |
| **go-xslt** (second engine) | No (pure Go binary) | Small, fast, Apache-2.0, supports streaming, schema-awareness and XSD 1.1 — features that SaxonC-HE lacks but CPI's Saxon-EE has | Independent implementation → may differ from Saxon in edge cases; pre-1.0, single maintainer, no PRs accepted; extension functions Unknown (F12) |
| Saxon-EE (bring-your-own, optional) | Yes (uses the Groovy JRE) | Closest to CPI if you own a licence | Commercial; nothing EE is shipped |

**Engine comparison mode:** run one test on both native engines and diff the outputs. Agreement gives high confidence; a difference points to an edge case worth checking on the tenant. Because CPI is Saxon, a SaxonC-HE result is treated as the reference unless the stylesheet uses EE-only features, where go-xslt is the better proxy.

### 5.3 Tooling
- XSLT editor + input XML + params table (pre-filled from the test case's headers/properties) + output with pretty-print and diff.
- Error messages with line/column linking back into the editor.
- `xsl:message` output in a console.
- Warnings for constructs CPI won't run (e.g. reflexive Java calls to your own classes are not supported in CPI [S8]).
- Trace mode (template matches) — later phase.

---

## 6. XML Tools (no Java)

- **XSD validation** with XSD 1.0 (CPI's XML Validator) and optional XSD 1.1, includes/imports resolved locally; errors with line/column. Engine: go-xslt's validator (F10); cross-check option with SaxonC-HE (HE validates only partially — to be confirmed).
- **XPath tester**: XPath 1.0 (as used in Content Modifier / Router) and XPath 3.1, with a namespace mapping table like the iFlow's "Namespace Mapping" setting.
- Later helpers (phase 4, only if wanted): XML↔JSON converter mimicking CPI's converter rules, Base64/URL encode, gzip, pretty-print/minify, XML diff.

---

## 7. Licences of shipped components (to confirm in the spike)

| Component | Licence | Note |
|---|---|---|
| Tauri, Monaco | MIT / Apache-2.0 | |
| go-xslt | Apache-2.0 (F10) | Its desktop app has a licence service — the **desktop app's** terms may differ from the library's; we only use the library/CLI |
| SaxonC-HE | MPL-2.0 (F14) | |
| Groovy, Apache Camel | Apache-2.0 | |
| JRE (e.g. Eclipse Temurin) | GPLv2 + Classpath Exception | |
| SAP Script API jar | SAP licence | **Not shipped**; optional import from your machine |

---

## 8. Runtime profiles and fidelity

### 8.1 What a profile is
A versioned JSON file describing one CPI runtime:
```json
{
  "id": "cpi-groovy-2x",
  "label": "Groovy Script 2.x",
  "java": "17",
  "groovy": "4.0.29",
  "camel": "3.14.7",
  "scriptApi": "v2",
  "evidence": "F2, F3",
  "verifiedOn": null
}
```
Shipped defaults: `cpi-groovy-1x`, `cpi-groovy-2x`, `cpi-xslt-saxonc-he`, `cpi-xslt-go-xslt`.

### 8.2 Dependency delivery
- **Bundled**: everything in the installer — offline from the first run, bigger download.
- **Fetched once**: base installer contains shell + native XML engines; JRE and Groovy jars are downloaded on first Groovy use and cached; offline afterwards.

### 8.3 Isolation and safety
Each run: fresh exchange, configurable timeout (default 10 s), memory cap per worker, captured stdout/stderr. XML engines: no network, file access limited to the project folder (go-xslt has this by default, F10). Groovy: network allowed by default (CPI scripts may make HTTP calls), with an "offline sandbox" toggle.

### 8.4 Calibration against your tenant (black-box observation)
We ship a small **fingerprint Groovy script** and **fingerprint XSLT** that you deploy once in a test iFlow. They report Java version, Groovy version (`GroovySystem.getVersion()`), Camel version, Saxon product/version (`system-property('xsl:product-version')`), default charset, timezone, locale. You paste the output into the app, which updates the profiles and moves F3/F4/F8 from "community report" to "verified on your tenant".

A second set of **probe cases** (type conversions, serialization defaults, `format-dateTime`, header-as-param behaviour) can be run on the tenant and stored as expected results — these become the regression tests that prove the simulator matches CPI.

---

## 9. Fidelity boundaries (what the simulation will not match)

- Adapter-specific headers (e.g. `CamelHttpResponseCode`) exist only if you set them in the input — test-case templates help here.
- Stream semantics: in CPI the body can be a one-shot stream; the simulator can deliver the body as a non-resettable `InputStream` to reproduce "body already consumed" bugs.
- Tenant-specific settings: memory limits, timezone, custom libraries, platform API data.
- XSLT: Saxon-version differences (SaxonC-HE 12/13 vs. CPI's 9.9 line) and EE-only features; mitigated by engine comparison and tenant probes.
- SAP-internal classes not part of the public script API cannot be simulated.

---

## 10. Project and test-case format (git-friendly)

```
my-project/
  cpi-workbench.json          # project settings, default profiles
  scripts/                    # .groovy files
  mappings/                   # .xsl / .xslt
  schemas/                    # .xsd
  libs/                       # extra .jar files
  platform/                   # mocked secure store, value mappings (JSON/CSV)
  tests/
    order-to-idoc/
      case.json               # profile, function name, headers, properties (typed)
      input.xml
      expected-output.xml     # optional: turns a case into a regression test
```
- Plain files, so they can live next to an exported iFlow in git.
- A case with an `expected-*` file becomes an assertion.
- **Headless CLI** (`cpiwb run tests/`) runs all cases and exits non-zero on failure — usable in CI.

---

## 11. Roadmap

| Phase | Scope | Result |
|---|---|---|
| **0 — Spikes** (risk first) | (a) Tauri ⇄ go-xslt sidecar round trip; (b) SaxonC-HE via Rust FFI on Windows + macOS (build, size, licence); (c) `cpi:setHeader` driver-stylesheet trick on both engines; (d) Groovy 2.4.21 on Java 17 vs. 8 with the v1 shim; (e) Groovy 4.0.29 + Camel 3.14 exchange wrapper; (f) installer size with and without JRE | Go/no-go per engine, measured sizes and startup times |
| **1 — MVP XSLT + XSD** (no Java) | XSLT simulator (params, `cpi:` functions, includes), XSD validation, XPath tester, engine comparison, save/load test cases | Usable daily for mappings |
| **2 — Groovy** | Groovy v1 + v2 simulator, typed headers/properties, MPL view, console, errors, diff | Covers both mapping types |
| **3 — Productivity** | Platform API mocks, compatibility linting, run-under-both-profiles, CLI runner, tenant calibration + probe cases | Regression testing, v1→v2 migration support |
| **4 — Polish & distribution** | Signed installers (`.msi`, notarized `.dmg`), auto-update, themes, optional helpers (§6) | Shareable with colleagues |

XSLT moved ahead of Groovy because it needs no JVM and has the fewest unknowns once the spikes pass.

Testing strategy: golden-file tests for every simulated API; a corpus of real CPI scripts/XSLTs (yours, anonymised); tenant probe results (§8.4) as expected values; W3C conformance is the engines' job, CPI fidelity is ours.

---

## 12. Open questions for you

1. **Shell**: OK with Rust/Tauri + web UI (my recommendation), or Go + Wails?
2. **Installer**: bundle everything (offline from first run, larger) or fetch the Groovy runtime on first use?
3. **Tenant calibration**: can you run the fingerprint and probe iFlows (§8.4) on a test tenant? This is the biggest lever for fidelity.
4. **Script API v2**: can you provide the SAP API docs or the Script API jar for v2, so the method list (F6) is exact? I could not reach help.sap.com from this environment.
5. **LinkedIn post**: is the tool you saw `tim-riep/go-xslt` (F11)? If it is a different one, please send the name or link.
6. **Saxon-EE**: do you or your company own a Saxon-EE licence (bring-your-own), or are SaxonC-HE + go-xslt enough?
7. **Platform APIs**: which do your scripts use most — Secure Store, Value Mapping, Keystore, Partner Directory, Data Store?
8. **Scope extras**: XML↔JSON conversion with CPI rules, or Message Mapping — on the roadmap or not?
9. **Name / licence**: app name, and should the repository be open source (which licence)?

---

## Appendix A — Research checklist (REA-style)

| Question | Status |
|---|---|
| Groovy versions per script step version | Answered (F1, F2) |
| Java version per script step version | Partial — 2.x reported (F3), 1.x Unknown (F4) → tenant fingerprint |
| v1 API surface | Answered (F5) |
| v2 API surface | Partial — package known, methods Unknown (F6) → Q4 |
| XSLT engine and version in CPI | Partial — Saxon-EE confirmed, version conflicting (F8) → tenant fingerprint |
| Can XSLT 3.0 / XSD run without Java? | Answered — yes: go-xslt, SaxonC-HE (F10, F14) |
| Can Groovy run without a JVM? | Answered — no faithful option (F16, F17) |
| Rust-native XSLT 3.0 engine? | Answered — none mature (F15) |
| go-xslt extension functions | Open (F12) → spike 0(c) |
| SaxonC from Rust | Open (F14) → spike 0(b) |
| Existing tools to reuse | Partial — go-xslt reusable as library/CLI; web IDEs are closed, hosted services (F18) |

## Sources

- [S1] SAP Help — Define a Local Script Step: https://help.sap.com/docs/r/368c481cd6954bdfa5d0435479fd4eaf/Cloud/en-US/03b32eb2c5c249f0a59bcd27c44d1e4e.html
- [S2] SAP Community — Testing Cloud Integration Groovy 4: https://community.sap.com/t5/integration-forum/testing-cloud-integration-groovy-4/m-p/14317619 ; Equalise IT — Upgrade to Groovy 4.0.x: https://equaliseit.com/upgrade-to-groovy-4-0-x-in-sap-cloud-integration/
- [S3] SAP Community — Upgrade to script version 2.x: https://community.sap.com/t5/technology-blog-posts-by-members/how-to-fix-groovy-script-warnings-and-upgrade-to-script-version-2-x-in-sap/ba-p/14351691
- [S4] SAP Community — Testing Groovy scripts locally (IntelliJ): https://blogs.sap.com/2022/03/03/testing-groovy-script-locally-using-intellij-idea/
- [S5] SAP Community — Groovy script debugging in Eclipse (2024): https://answers.sap.com/t5/technology-blogs-by-members/groovy-script-debugging-in-eclipse-2024/ba-p/13582941
- [S6] SAP Community — How do you test your Groovy scripts: https://community.sap.com/t5/technology-blogs-by-members/how-do-you-test-your-groovy-scripts/bc-p/13327543
- [S7] SAP Community — XSLT Mapping enriched with XSLT 3.0: https://community.sap.com/t5/technology-blog-posts-by-sap/cloud-integration-xslt-mapping-is-enriched-with-xslt-3-0-specification/ba-p/13415753
- [S8] SAP Community — Peeking under the hood of CPI's XSLT processor: https://community.sap.com/t5/technology-blog-posts-by-members/peeking-under-the-hood-of-cpi-s-xslt-processor/ba-p/13322917
- [S9] SAP Community — Migrating XSLT mappings from PO to Integration Suite: https://community.sap.com/t5/integration-blog-posts/migrating-xslt-mappings-from-sap-process-orchestration-to-sap-integration/ba-p/14284726
- [S10] go-xslt: https://github.com/tim-riep/go-xslt
- [S11] helium/xslt3: https://pkg.go.dev/github.com/lestrrat-go/helium/xslt3
- [S12] speedata/goxslt: https://pkg.go.dev/github.com/speedata/goxslt
- [S13] go-xslt/xslt (XSLT 1.0): https://github.com/go-xslt/xslt
- [S14] SaxonC product information: https://www.saxonica.com/html/saxon-c/index.html ; SaxonC 12 docs: https://saxonica.com/saxon-c/doc12/html
- [S15] SaxonC-HE on PyPI: https://www.pypi.org/project/saxonche/12.2.0/
- [S16] xrust: https://docs.rs/xrust
- [S17] Running Groovy natively without the JVM (2004): https://joewalnes.com/2004/01/07/running-groovy-natively-without-the-jvm
- [S18] Camel Quarkus Groovy extension: https://camel.apache.org/camel-quarkus/reference/extensions/groovy.html
- [S19] Spring Boot with GraalVM (known limitations): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-with-GraalVM/Spring-Boot-Config-Data-Migration-Guide
- [S20] Contiva Groovy IDE: https://ide.contiva.com/
- [S21] GrooveBox: https://grooveboxide.com/guides
- [S22] ArtCraft notes: https://notes.nicolasdeville.com/apps/artcraft/
- [S23] REA workflow skill: https://github.com/morluto/rea/blob/main/.agents/skills/reverse-engineer-anything/SKILL.md
- Saxonica — XSLT 3.0 support: https://www.saxonica.com/html/documentation/using-xsl/xslt30.html
