# Integration Suite Local Workbench — Project Plan

Status: **Draft for review**. Nothing is implemented yet. Please review the open questions in §11 before work starts.

Working name: **CPI Workbench** (placeholder; rename freely).

---

## 1. Goal

A small, fast, **fully offline** desktop app for Windows and macOS (Linux as a free side effect) to develop and test the two kinds of mapping logic most often used in SAP Integration Suite / Cloud Integration (CPI):

1. **Groovy Scripts** — both script step generations:
   - **Script API v1** (Groovy Script step 1.x)
   - **Script API v2** (Groovy Script step 2.x)
2. **XSLT Mappings** — XSLT 1.0/2.0/3.0 as executed by the CPI XSLT Mapping step (version 1.2+ supports XSLT 3.0).

Plus supporting XML tooling: **XSD validation**, XPath evaluation, and later small helpers.

The app simulates the CPI runtime contract (message body, headers, exchange properties, attachments, logging, selected platform APIs) closely enough that a script or stylesheet that works locally behaves the same once deployed. It is a **simulation**, not the SAP runtime — §8 lists where it can and cannot match.

### Non-goals (for now)
- Running whole iFlows, adapters, routers, or Message Mappings (graphical mappings).
- Connecting to a tenant (deployment, downloading artifacts). This could come later as an opt-in; the core stays offline.
- Shipping SAP proprietary code or jars.

---

## 2. Research summary (what is known, and how sure we are)

| Topic | Finding | Confidence / source |
|---|---|---|
| Groovy version, script step 1.x | Groovy **2.4.21** | High — SAP Help "Define a Local Script Step" and several community sources agree |
| Groovy version, script step 2.x | Groovy **4.0.29** | High — same SAP Help page; matching third-party tools |
| Java runtime | A community diagnostic on a v2 step reported **Java 17.0.18, Groovy 4.0.29, Camel 3.14.7**. The SAP Help page still mentions Java 8 standard libraries (possibly outdated). Which JVM runs v1 scripts today is **unclear**. | Medium — community post only; **must verify on your tenant** (see §7.4) |
| v1 API | `com.sap.gateway.ip.core.customdev.util.Message`, entry function `def Message processData(Message message)` | High |
| v2 API | Package `com.sap.it.script.v2.api` (e.g. `com.sap.it.script.v2.api.Message`, `com.sap.it.script.v2.api.Exchange`). Full method surface **not yet verified** — I could not reach SAP's API reference from this environment. | Medium |
| SAP Script API jar | Downloadable from SAP Development Tools (tools.hana.ondemand.com → Cloud Integration). Community reports that it is a "bare-bones" jar and some versions lack the `Message` class. It is under an SAP license, so we **must not bundle it**. | Medium |
| XSLT processor in CPI | **Saxon-EE** (commercial). A Nov-2025 community post reports **Saxon-EE 9.9.1.6**; an older post reported 9.8.0.12. Before April 2019 CPI used Saxon-HE. | Medium — community posts; verify with `system-property('xsl:product-version')` in your tenant |
| XSLT 3.0 | Supported since XSLT Mapping step version 1.2 (April 2019 release). | High — SAP blog / Help |
| Existing tools | Web IDEs exist for CPI Groovy (e.g. **Contiva Groovy IDE** `ide.contiva.com`, **GrooveBox** `grooveboxide.com` — claims Groovy 2.4/Java 8 and Groovy 4/Java 17 with typed headers/properties). The common local approach in SAP blogs is a hand-built `MessageImpl` stub plus a test harness in Eclipse/IntelliJ. I found **no** existing offline desktop tool that combines Groovy v1+v2 and XSLT 3.0 with CPI semantics. | Medium — I could only read search snippets, not the tools themselves |

**Implication:** the critical fidelity parameters (exact Java version per step version, Saxon version, Camel version) drift as SAP updates the runtime. The app therefore treats them as **runtime profiles** that you calibrate against your own tenant once (§7.4), instead of hard-coding guesses.

Note on "Reverse Engineer Anything": that tool/skill is not available in this session, and the web IDEs above are closed-source hosted services, so nothing is reused from them. The plan relies on public Groovy/Saxon/Camel artifacts and a **clean-room** implementation of the documented CPI script interfaces.

---

## 3. Technology decision

### 3.1 The hard constraint
Groovy and Saxon are **JVM** technologies. Whatever the UI is written in, scripts must execute on a real JVM with the real Groovy 2.4.21 / 4.0.29 jars — otherwise the simulation is not faithful. Rust cannot run Groovy. So "Rust vs. something else" is only a question about the **shell/UI**, not about the engine.

### 3.2 Options

| Option | Pros | Cons |
|---|---|---|
| **A. Tauri 2 (Rust) shell + web UI + JVM sidecar engine** | Small native shell; Monaco editor (the VS Code editor: Groovy/XML syntax, diff view, minimap) comes for free; modern UI; Rust handles process supervision, file IO, packaging, auto-update | Two languages (Rust + TypeScript UI) plus Kotlin/Java engine; still ships a JRE (~45–70 MB via `jlink`) |
| **B. Pure JVM desktop (Kotlin + Compose Multiplatform or JavaFX)** | One language for UI and engine; no IPC | Code editor components (RSyntaxTextArea etc.) are weaker than Monaco; heavier startup; less polished look out of the box |
| **C. Pure Rust native UI (egui/wgpu, the ArtCraft approach)** | Very small, very fast UI | egui has no good code editor with Groovy/XSLT highlighting, diffing, etc.; still needs the JVM sidecar anyway, so the main benefit (no runtime) is lost |
| D. Electron + JVM sidecar | Mature ecosystem | Largest footprint; nothing it offers that Tauri doesn't here |

About ArtCraft: per the one source I found, its apps are **pure Rust with egui/wgpu, no webview** — not Tauri. That stack fits graphics apps. Our app is mainly text editors and trees, where a webview plus Monaco is clearly stronger.

### 3.3 Recommendation: **Option A**

```
┌──────────────────────────── Tauri app (Rust) ───────────────────────────┐
│  Web UI (TypeScript + Svelte or React, Monaco editor)                    │
│        │  Tauri commands / events                                        │
│  Rust core: project files, settings, process manager, packaging          │
│        │  JSON-RPC over stdio (one long-lived process per runtime)       │
└────────┼─────────────────────────────────────────────────────────────────┘
         ▼
┌──────────── Engine workers (Kotlin/Java, bundled JRE via jlink) ────────┐
│  worker-groovy-v1   isolated classpath: Groovy 2.4.21 + v1 API shim     │
│  worker-groovy-v2   isolated classpath: Groovy 4.0.29 + v2 API shim     │
│  worker-xml         Saxon-HE (XSLT 3.0 / XPath 3.1), XSD validation     │
└──────────────────────────────────────────────────────────────────────────┘
```

Why separate worker **processes**, not just classloaders:
- Groovy 2.4 and 4.0 must never leak into each other (different `groovy.*` packages and ASTs).
- If calibration shows v1 still runs on Java 8 in CPI, the v1 worker can run on a Java 8/11 JRE while v2 runs on Java 17 — a classloader cannot switch JVMs.
- A runaway script (infinite loop, `System.exit`, OOM) kills only the worker; the UI restarts it. Timeouts and memory limits are enforced per run.
- Workers stay warm, so a typical run takes milliseconds after the first start.

If you would rather keep everything in one language, Option B is the fallback; the engine design (§4–§6) is identical either way.

---

## 4. Groovy Script Simulator

### 4.1 Input panel (mirrors the CPI exchange)
- **Body**: text editor with mode switch (XML / JSON / plain / binary from file). Encoding selector (UTF-8 default). Pretty-print buttons.
- **Headers** table: name, value, **Java type** (`String`, `Integer`, `Long`, `Boolean`, `byte[]`, `Date`, `null`, …). CPI scripts often break on types, so types are first-class.
- **Exchange properties** table: same as headers.
- **Attachments**: name, content type, file.
- **Script**: Monaco editor; **function name** field (default `processData`, as in the CPI step configuration).
- **Script collection / libraries**: add other `.groovy` files and `.jar` files to the classpath, like resources/script collections referenced by an iFlow.
- **Runtime profile**: `Groovy Script 1.x` or `Groovy Script 2.x` (§7).

### 4.2 Output panel
- Resulting body (with **diff against input**), headers and properties (added / changed / removed highlighted, including type changes).
- **MPL view**: what the script wrote via `messageLogFactory.getMessageLog(message)` — custom header properties, string properties, log attachments (`addAttachmentAsString`).
- Console (`println`, `System.out/err`), logger output (SLF4J).
- Exception view: the exception as CPI would surface it, with the stack trace filtered to script line numbers.
- Timing and memory used.

### 4.3 Simulated API surface (clean-room)
Implemented by us, matching public package and class names so scripts compile unchanged:

**v1** (`com.sap.gateway.ip.core.customdev.util.Message`):
- `getBody()`, `getBody(Class)`, `setBody(Object)`
- `getHeaders()`, `getHeader(String, Class)`, `setHeader`, `setHeaders`
- `getProperties()`, `getProperty`, `setProperty`, `setProperties`
- `getAttachments()`, `setAttachments`, `addAttachmentObject`, …
- `getBodySize()`, `getAttachmentsSize()`
- `messageLogFactory` binding → `MessageLog` with `setStringProperty`, `addCustomHeaderProperty`, `addAttachmentAsString`

**v2** (`com.sap.it.script.v2.api.*`): Message/Exchange API as documented by SAP for script step 2.x. The exact method list must be taken from the SAP API reference before implementing (open item — §11).

**Type conversion fidelity:** in CPI, `getBody(String)` / `getHeader(name, Type)` go through **Apache Camel's type converters**. Instead of re-implementing those rules, the shim wraps a real Apache Camel `DefaultExchange` (Camel 3.14.x for v2 per the diagnostic above; the v1 Camel version is to be calibrated). This gives realistic conversions for `InputStream`, `byte[]`, `String`, `Reader`, `org.w3c.dom.Document`, etc. — a frequent source of "works locally, fails on CPI" bugs.

**Platform APIs** (`com.sap.it.api.ITApiFactory`): mocked and driven by a local, per-project JSON file:
- `SecureStoreService` → user credentials (local fake values; never real secrets in project files by default)
- `ValueMappingApi` → value mapping tables (import from CSV)
- `KeystoreService` → local test keystore
- `PartnerDirectoryService`, `DataStore`, number ranges → later phases

Anything not simulated throws a clear `UnsupportedInSimulator` error naming the API, instead of failing silently.

### 4.4 Compatibility linting (static checks before running)
- Flags v1 → v2 migration issues: imports of the v1 `Message` in a v2 profile, Groovy 2 → 4 breaking changes (e.g. `groovy.util.XmlSlurper` moved to `groovy.xml.XmlSlurper`, removed/legacy packages, stricter static compilation behaviour), wildcard imports (SAP's upgrade guidance discourages them).
- Flags Java APIs not available on the profile's Java version.
- Optional "run under both profiles" button to compare results side by side — useful while upgrading scripts to 2.x.

---

## 5. XSLT Mapping Simulator

### 5.1 Behaviour to mimic from the CPI XSLT Mapping step
- Input = message body (or, where the step version allows it, a header/property as source — **to verify**).
- **Headers and exchange properties are available as `xsl:param`** by name, as in CPI.
- Setting headers/properties from XSLT via the `$exchange` parameter and SAP's extension functions (`cpi:setHeader`, `cpi:setProperty`, namespace `http://sap.com/it/`) — simulated by registering equivalent extension functions. **Exact signatures to verify against SAP Help.**
- Output as body; headers/properties changes shown like in the Groovy simulator.
- Multiple stylesheets with `xsl:import` / `xsl:include` resolved from the project folder (like iFlow/mapping resources).

### 5.2 Saxon edition — an honest limitation
CPI runs **Saxon-EE** (commercial). We can only bundle **Saxon-HE** (open source, MPL-2.0). Differences that matter:
- HE lacks schema-aware XSLT (`xsl:import-schema`, typed validation), streaming (`xsl:stream`), and **reflexive Java extension calls** (`java:` namespace calls to JDK classes). Since Saxon 10, HE does support higher-order functions and `xsl:evaluate`.
- The app detects EE-only constructs and warns before running.
- **Bring-your-own-EE**: if you own a Saxon-EE license, you can point the app to your `saxon-ee.jar` + license file and it will use it. Nothing EE-related is shipped.

Version choice: default to **Saxon-HE 12.x** (current XSLT 3.0 / XPath 3.1 implementation) with an optional **Saxon-HE 9.9.1-x** profile, because CPI reportedly uses the 9.9 line. Results between 9.9 and 12 can differ in edge cases (error messages, some function behaviour), so matching the line matters for fidelity.

### 5.3 Tooling
- XSLT editor + input XML + params table + output with pretty-print and diff.
- Saxon error messages with line/column linking back into the editor.
- `xsl:message` output in a console.
- Trace mode (template matches) — later phase.

---

## 6. XML Tools

- **XSD validation**: validate any XML against an XSD (with includes/imports resolved locally). Default XSD 1.0 via the JDK's JAXP/Xerces, mirroring CPI's XML Validator step. Optional XSD 1.1 via Apache Xerces-J (xsd11 build) — only useful if you use 1.1 schemas. Errors listed with line/column.
- **XPath tester**: XPath 1.0 (as used in CPI Content Modifier / Router with `javax.xml.xpath`) and XPath 3.1 (Saxon). Namespace mapping table like the iFlow's "Namespace Mapping" setting.
- Later helpers (phase 4, only if wanted): XML↔JSON converter that mimics CPI's converter rules, Base64/URL encode, gzip, pretty-print/minify, XML diff.

---

## 7. Runtime profiles and fidelity

### 7.1 What a profile is
A versioned JSON file describing one CPI runtime:
```json
{
  "id": "cpi-groovy-2x",
  "label": "Groovy Script 2.x",
  "java": "17",
  "groovy": "4.0.29",
  "camel": "3.14.7",
  "scriptApi": "v2",
  "verifiedOn": "2026-xx-xx (tenant build ...)"
}
```
Shipped defaults: `cpi-groovy-1x`, `cpi-groovy-2x`, `cpi-xslt-saxon-he-12`, `cpi-xslt-saxon-he-9.9`.

### 7.2 Dependency resolution
Groovy, Camel and Saxon-HE are on Maven Central. Two options (decision in §11):
- **Bundled**: everything shipped in the installer — truly offline from the first run, bigger download.
- **Fetched once**: the app downloads the jars for a profile on first use and caches them; offline afterwards.

### 7.3 Isolation and safety
Each run: fresh exchange, configurable timeout (default 10 s), memory cap per worker, captured stdout/stderr. No network restrictions by default (scripts in CPI can do HTTP calls), but an "offline sandbox" toggle can block sockets.

### 7.4 Calibration against your tenant
We ship a small **fingerprint Groovy script** and **fingerprint XSLT** that you deploy once in a test iFlow. They output Java version, Groovy version (`GroovySystem.getVersion()`), Camel version, Saxon product/version (`system-property('xsl:product-version')`), default charset, timezone and locale. You paste the result into the app and it creates or updates a profile. This turns the "medium confidence" items in §2 into verified facts for your landscape.

---

## 8. Fidelity boundaries (what the simulation will not match)

- Adapter-specific headers (e.g. `CamelHttpResponseCode`, SFTP headers) exist only if you set them in the input — test-case templates help here.
- Stream semantics: in CPI the body can be a one-shot stream; the simulator can optionally deliver the body as a non-resettable `InputStream` to reproduce "body already consumed" bugs.
- Tenant-specific settings: memory limits, timezone, installed custom libraries, platform API data.
- Saxon-EE-only features (§5.2) unless you bring your own EE license.
- SAP-internal classes not part of the public script API cannot be simulated.

---

## 9. Project and test-case format (git-friendly)

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
- Plain files, so they can live next to an exported iFlow in a git repository.
- A case with an `expected-*` file becomes an assertion.
- **Headless CLI** (`cpiwb run tests/`) runs all cases and exits non-zero on failure — usable in CI pipelines (phase 3).

---

## 10. Roadmap

| Phase | Scope | Result |
|---|---|---|
| **0 — Spikes** (risk first) | (a) v1 shim + Groovy 2.4.21 on Java 17 vs Java 8; (b) Groovy 4.0.29 + Camel 3.14 exchange wrapper; (c) Saxon-HE 12 with `xsl:param` + `cpi:setHeader` emulation; (d) Tauri ⇄ JVM JSON-RPC round trip + `jlink` JRE size on Windows/macOS | Go/no-go on architecture, real numbers for installer size and startup time |
| **1 — MVP Groovy** | Groovy v1 + v2 simulator, typed headers/properties, MPL view, console, errors, diff, save/load test cases | Usable daily for scripts |
| **2 — XSLT + XSD** | XSLT simulator (params, extension functions, includes), XSD validation, XPath tester | Covers both mapping types |
| **3 — Productivity** | Platform API mocks (secure store, value mappings), compatibility linting, run-under-both-profiles, CLI runner, calibration import | Regression testing, v1→v2 migration support |
| **4 — Polish & distribution** | Signed installers (`.msi` on Windows, notarized `.dmg` on macOS), auto-update, themes, optional helpers (§6) | Shareable with colleagues |

Rough engine split: Kotlin for the workers (Groovy itself interoperates trivially with Kotlin/Java), Rust only for the shell, TypeScript for the UI.

Testing strategy: golden-file tests for every simulated API method; a corpus of real-world CPI scripts/XSLTs (yours, anonymised) as regression tests; values recorded from the tenant fingerprint (§7.4) as expected results.

---

## 11. Open questions for you

1. **Shell choice**: OK with Option A (Tauri + web UI + JVM workers)? Or prefer Option B (one language, pure JVM)?
2. **Offline from first run** (bundled jars, larger installer ~150–250 MB estimate) vs. **fetch once per profile** (smaller installer)?
3. **Tenant calibration**: can you run the fingerprint script/XSLT (§7.4) on a test tenant? This is the single biggest lever for fidelity.
4. **Script API v2 reference**: do you have access to the SAP API docs / Script API jar for v2 so we can align the method surface exactly? (I could not reach help.sap.com from this environment.)
5. **Saxon-EE**: do you or your company own a Saxon-EE license (bring-your-own-EE), or is HE + warnings enough?
6. **Platform APIs**: which do your scripts use most — Secure Store, Value Mapping, Keystore, Partner Directory, Data Store?
7. **Scope extras**: should XML↔JSON conversion with CPI rules, or Message Mapping, be on the roadmap at all?
8. **Name / license**: app name, and should the repository be open source (and under which license)?

---

## Sources

- SAP Help — Define a Local Script Step (Groovy 2.4.21 for step 1.1, 4.0.29 for step 2.0): https://help.sap.com/docs/r/368c481cd6954bdfa5d0435479fd4eaf/Cloud/en-US/03b32eb2c5c249f0a59bcd27c44d1e4e.html
- SAP Community — How to fix Groovy script warnings and upgrade to script version 2.x: https://community.sap.com/t5/technology-blog-posts-by-members/how-to-fix-groovy-script-warnings-and-upgrade-to-script-version-2-x-in-sap/ba-p/14351691
- SAP Community — Testing Cloud Integration Groovy 4 (v2 import paths): https://community.sap.com/t5/integration-forum/testing-cloud-integration-groovy-4/m-p/14317619
- Equalise IT — Upgrade to Groovy 4.0.x in SAP Cloud Integration: https://equaliseit.com/upgrade-to-groovy-4-0-x-in-sap-cloud-integration/
- SAP Community — Groovy script debugging in Eclipse (2024): https://answers.sap.com/t5/technology-blogs-by-members/groovy-script-debugging-in-eclipse-2024/ba-p/13582941
- SAP Community — How do you test your Groovy scripts (Script API jar discussion): https://community.sap.com/t5/technology-blogs-by-members/how-do-you-test-your-groovy-scripts/bc-p/13327543
- SAP Community — XSLT Mapping enriched with XSLT 3.0: https://community.sap.com/t5/technology-blog-posts-by-sap/cloud-integration-xslt-mapping-is-enriched-with-xslt-3-0-specification/ba-p/13415753
- SAP Community — Peeking under the hood of CPI's XSLT processor: https://community.sap.com/t5/technology-blog-posts-by-members/peeking-under-the-hood-of-cpi-s-xslt-processor/ba-p/13322917
- SAP Community — Migrating XSLT mappings from PO to Integration Suite (Saxon-EE 9.9.1.6): https://community.sap.com/t5/integration-blog-posts/migrating-xslt-mappings-from-sap-process-orchestration-to-sap-integration/ba-p/14284726
- Saxonica — XSLT 3.0 support: https://www.saxonica.com/html/documentation/using-xsl/xslt30.html
- Contiva Groovy IDE: https://ide.contiva.com/
- GrooveBox: https://grooveboxide.com/guides
- ArtCraft notes (pure Rust, egui/wgpu): https://notes.nicolasdeville.com/apps/artcraft/
