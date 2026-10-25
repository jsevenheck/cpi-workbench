# CPI Workbench

A lean, offline desktop app for testing **SAP Integration Suite (Cloud Integration)** mappings and
scripts locally: enter a message (body, headers, exchange properties), run an **XSLT mapping** or a
**Groovy script** and see the resulting body, headers, properties and log entries immediately,
without deploy → trace → monitor round trips.

The goal is to behave like the real CPI step, not like a generic XSLT tester or Groovy console.

**XSLT Mapping**

* **Same engine family:** Saxon-HE **9.9** (the release line CPI runs, as Saxon-EE 9.9) or Saxon-HE 12.
* **CPI parameter binding:** every global `xsl:param` gets the header or exchange property with
  the same name, passed as Java objects the way Camel's XSLT component does (typed values work).
* **`$exchange` + `hci:setHeader` / `hci:setProperty`** (namespace `http://sap.com/it/`) change the
  output message, as in CPI.
* Includes/imports resolve inside the project folder like iFlow resources; nothing leaves the machine.

**Groovy Script**

* **Same Groovy versions as CPI:** Groovy **2.4.21** for script step 1.x and **4.0.29** for 2.x, on Java 17.
* **`Message` backed by a real Apache Camel exchange** (Camel 2.24 / 3.14), so `getBody(String)`,
  `getBody(Reader)`, `getHeader(name, Integer)` and case-insensitive header names behave like CPI.
* `messageLogFactory` with MPL properties, custom header properties and attachments;
  `ITApiFactory` with **Secure Store**, **Value Mapping**, **Partner Directory** and **Keystore** mocks
  fed from the project's `platform/` folder.
* Compile and runtime errors point to the script line; `println` output is captured.
* Groovy 1.x → 2.x differences surface for real (e.g. `groovy.util.XmlSlurper` no longer exists in Groovy 4).

See [PLAN.md](PLAN.md) for the research behind this and the open points.

![XSLT view](docs/screenshot-xslt.png)

![Groovy view](docs/screenshot-groovy.png)

## What is in the box

| Path | What |
|---|---|
| `engine/` | Java 17 engines with a JSON-RPC interface, test runner and CLI. **XML:** CPI XSLT step, XSD validation, XPath — built against Saxon-HE 9.9.1-8 (`xml-he99`) and 12.9 (`xml-he12`) and compiled to **native executables** with GraalVM (no JVM at runtime). **Groovy:** `groovy-v1` (Groovy 2.4.21, Camel 2.24.3, Script API v1) and `groovy-v2` (Groovy 4.0.29, Camel 3.14.7, Script API v2) — run on a trimmed Java 17 runtime. |
| `app/` | Desktop app: Tauri 2 (Rust) shell + Svelte/Monaco UI. Starts the engines as sidecar processes on demand. |
| `examples/demo-project/` | Sample project: mapping with include, schema, test cases. |
| `probes/` | Fingerprint and behaviour probes to run once on a real tenant, to verify the simulation. |
| `PLAN.md` | Plan, research ledger, open questions. |

Measured on a Linux x86-64 build machine:

| | XSLT (native) | Groovy 1.x (JVM) | Groovy 2.x (JVM) |
|---|---|---|---|
| Engine start to ready | ≈ 10 ms | ≈ 220 ms (with AppCDS, 440 ms without) | ≈ 230 ms (410 ms without) |
| First run | ≈ 10 ms | ≈ 170 ms | ≈ 420 ms |
| Later runs (cached) | < 1 ms | ≈ 2 ms | ≈ 2 ms |
| Peak memory | ≈ 60 MB (270 KB input) | ≈ 75 MB | ≈ 150 MB |
| Size | 48 / 54 MB executables (≈ 16 / 18 MB compressed) | jars 14 + 12 MB, Java 17 runtime 75 MB (≈ 47 MB compressed) | |

The Groovy runtime starts only when the Groovy tab is used and stops after 10 idle minutes.

## Using it

### Desktop app

Download the installer for your platform from the CI artifacts (see *Building* below), install it,
open a project folder (any folder; **Init** adds `mappings/`, `schemas/`, `tests/`). Then:

* open a `.xsl` in the tree, enter input body / headers / properties on the left,
* the output updates live (or press **Ctrl+Enter**); compile and runtime errors are marked in the editor,
* **Parameters** shows how each `xsl:param` was bound (header, property, default),
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  Handy before migrating a script from 1.x to 2.x,
* **Save as test** stores input, mapping reference and the current output as a regression test,
* **Tests** runs all test cases of the project on the right engine,
* **Import iFlow** unpacks an iFlow exported from Integration Suite (zip) into `iflows/` and lists
  its Groovy Script and XSLT Mapping steps; clicking a step opens its file with the step's function
  name and script version. The `.iflw` format is not publicly documented, so this is read
  generically (best effort).

The installers are not code-signed yet: on macOS, right-click → *Open* the first time; on Windows,
SmartScreen asks once ("More info" → "Run anyway"). The Windows installer installs per user, no admin rights.

### Command line (CI-friendly)

```sh
java -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in input.xml -H SenderSystem=ERP
java -jar cpiwb-groovy-v2.jar test examples/demo-project/tests
cpiwb-xml xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
cpiwb-xml validate --xsd schemas/order.xsd --in output.xml
cpiwb-xml xpath --expr "//Item/@sku" --in input.xml --version 3.1
cpiwb-xml test examples/demo-project/tests        # exit code 1 if a case fails
```

### Browser mode (no installation of the desktop shell)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode listens on localhost only. The engine serving the page handles files; add more engines with a URL parameter:

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```

## Test cases

`tests/<name>/case.json`:

```json
{
  "name": "Standard order",
  "step": "xslt",
  "profile": "saxon-he-9.9",
  "xslt": "../../mappings/order-to-salesorder.xsl",
  "input": {
    "body": { "file": "input.xml" },
    "headers": [ { "name": "SenderSystem", "value": "WEBSHOP", "type": "String" } ],
    "properties": { "CustomerGroup": "02" }
  },
  "options": { "paramPrecedence": "header", "missingParameter": "default" },
  "expect": {
    "body": { "file": "expected-output.xml" },
    "compare": "xml",
    "headers": { "SAP_ApplicationID": "WEB-1001" },
    "properties": { "ItemCount": "2" }
  }
}
```

* `step`: `xslt`, `groovy` (`script` instead of `xslt`, optional `function`, `options.bodyType`: `String` / `InputStream` / `byte[]`) or `xsd` (`schema`; expect `valid`, `errorContains`).
* Profiles: `saxon-he-9.9`, `saxon-he-12`, `groovy-1.x`, `groovy-2.x`.
* Header/property types: `String`, `Integer`, `Long`, `Boolean`, `Double`, `Float`, `BigDecimal`, `Date` (ISO-8601), `byte[]` (Base64), `null`.
* `compare`: `xml` (ignores formatting and namespace declarations), `text`, `trimmed`.
* `expect.ok: false` asserts that the mapping fails.

### Mocked platform data (`platform/`)

| File | Used by | Format |
|---|---|---|
| `credentials.json` | `SecureStoreService.getUserCredential` | `{ "ALIAS": { "username": "…", "password": "…", "properties": {…} } }` |
| `value-mappings.json` | `ValueMappingApi.getMappedValue` | `[ { "sourceAgency", "sourceIdentifier", "sourceValue", "targetAgency", "targetIdentifier", "targetValue" } ]` |
| `partner-directory.json` | `PartnerDirectoryService` | `{ "partners": { "PID": { "parameters": { "NAME": "string" or { "contentType": "…", "file": "x.bin" } or { "base64": "…" } }, "alternativePartners": [ { "agency", "scheme", "id" } ] } } }` |
| `keystore.json` | `KeystoreService` | `{ "file": "keystore.p12", "password": "…", "type": "PKCS12", "keyPasswords": { "alias": "…" } }` — the file lies next to it; `type` is optional |

A test case can override any of these with a `platform` object (`credentials`, `valueMappings`,
`partnerDirectory`, `keystore`); keys it leaves out still come from the folder. Use test-only keys
and passwords here — never real tenant secrets.

## Fidelity: what matches CPI and what does not (yet)

| Area | Status |
|---|---|
| Saxon release line (9.9) | Same line as CPI; CPI runs the **EE** edition, the simulator **HE** |
| Higher-order functions, `xsl:evaluate` | Not in HE 9.9 → use the *Modern HE (Saxon 12)* profile |
| Schema-aware XSLT, streaming | EE only; not available |
| `format-date` with languages other than English | HE prints English names (`[Language: en]…`); CPI's EE likely localises |
| Header vs. property with the same name | Header wins (Camel XsltBuilder order) — **to verify on a tenant** (probe Q-X2) |
| Declared parameter without header/property | XSLT default is used; switchable to "error" — **to verify** (Q-X4) |
| XSD validation | JDK validator, XML Schema 1.0 — CPI's validator **to verify** (Q-V1) |

| Groovy versions | Groovy 2.4.21 / 4.0.29 as documented by SAP |
| Java version | Java 17 (reported for 2.x); the 1.x Java version is **to verify** |
| Camel version | 3.14.7 for 2.x (reported); 2.24.3 for 1.x is an **assumption** |
| Script API v1 | Documented methods (body, headers, properties, attachments, sizes) |
| Script API v2 | **Provisional**: same methods as v1 until SAP's v2 reference is available (probe `fingerprint-v2.groovy` lists the real methods) |
| Platform APIs | Secure Store (user credentials), Value Mapping, Partner Directory (`getParameter` for String/`BinaryData`, `getPartnerId`, `getAlternativePartnerId`), Keystore (`getKey`, `getCertificate`, `getCertificateChain`, `getKeyPair`); others raise `UnsupportedInSimulator`. Missing Partner Directory entries return `null`; an unknown keystore alias throws — CPI's behaviour for both **to verify** |
| Data Store from scripts | Not simulated: CPI has no public script API for it (scripts use internal classes) |
| Groovy modules on 2.x | xml, json, dateutil, datetime, templates, nio bundled — CPI's set **to verify** |

Run the probes in `probes/` on a test tenant to turn the "to verify" items into facts.

## Building

Requirements: JDK 17+ and Maven; GraalVM 21+ for native engines; a JDK 17 with jmods for the
Groovy runtime (`engine/groovy/build-jre.sh`); Node 22; Rust (stable);
on Linux the WebKitGTK development packages (see `.github/workflows/build.yml`).

```sh
mvn -f engine/pom.xml verify                         # engine + tests for both Saxon versions
GRAALVM_HOME=/path/to/graalvm engine/native/build-native.sh   # native engines → engine/native/out
python3 engine/native/workload.py engine/native/out/cpiwb-xml-he99 serve --stdio   # smoke test

JAVA_HOME=/path/to/jdk17 engine/groovy/build-jre.sh /tmp/jre    # trimmed Java 17 for Groovy

cd app
npm ci
CPIWB_JRE_DIR=/tmp/jre node scripts/prepare-sidecars.mjs   # stage engines + JRE for bundling
npx tauri build                                      # installer for the current OS
npx tauri dev                                        # development (UI hot reload)
```

`.github/workflows/build.yml` builds Windows (.msi/.exe), macOS (Apple Silicon and Intel, .dmg)
and Linux bundles. Native images must be built on each target OS; there is no cross-compilation.

When a Saxon feature fails only in the native build, re-record the reachability metadata:
run `workload.py` with `-agentlib:native-image-agent=config-merge-dir=engine/xml-heXX/src/main/resources/META-INF/native-image/io.cpiwb/cpiwb-xml-heXX`
and regenerate `saxon-generated/reflect-config.json` with `engine/native/GenReflect.java`.

## Licences of bundled components

Saxon-HE: MPL-2.0 · Groovy, Apache Camel: Apache-2.0 · Tauri, Monaco: MIT/Apache-2.0 ·
OpenJDK runtime and GraalVM Native Image runtime: GPLv2 with Classpath Exception · JavaBeans
Activation (javax.activation): CDDL/GPLv2+CE · test corpus from pizug/cpi-groovy-examples: MIT.
No SAP code or jars are included; the `com.sap.*` classes in `engine/groovy` are clean-room
simulations written from SAP's public documentation.
