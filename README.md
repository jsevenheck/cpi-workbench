# CPI Workbench

A lean, offline desktop app for testing **SAP Integration Suite (Cloud Integration)** mappings
locally: enter a message (body, headers, exchange properties), run an **XSLT mapping** and see the
resulting body, headers and properties immediately, without deploy → trace → monitor round trips.

The goal is to behave like the real CPI step, not like a generic XSLT tester:

* **Same engine family:** Saxon-HE **9.9** (the release line CPI runs, as Saxon-EE 9.9) or Saxon-HE 12.
* **CPI parameter binding:** every global `xsl:param` gets the header or exchange property with
  the same name, passed as Java objects the way Camel's XSLT component does (typed values work).
* **`$exchange` + `hci:setHeader` / `hci:setProperty`** (namespace `http://sap.com/it/`) change the
  output message, as in CPI.
* Includes/imports resolve inside the project folder like iFlow resources; nothing leaves the machine.

Groovy Script simulation (Script API v1 and v2) is the next phase — see [PLAN.md](PLAN.md).

![XSLT view](docs/screenshot-xslt.png)

## What is in the box

| Path | What |
|---|---|
| `engine/` | Java 17 engine: CPI XSLT step, XSD validation, XPath tester, test runner, CLI, JSON-RPC. Built twice: `xml-he99` (Saxon-HE 9.9.1-8) and `xml-he12` (Saxon-HE 12.9), then compiled to **native executables** with GraalVM (no JVM needed at runtime). |
| `app/` | Desktop app: Tauri 2 (Rust) shell + Svelte/Monaco UI. Starts the engines as sidecar processes on demand. |
| `examples/demo-project/` | Sample project: mapping with include, schema, test cases. |
| `probes/` | Fingerprint and behaviour probes to run once on a real tenant, to verify the simulation. |
| `PLAN.md` | Plan, research ledger, open questions. |

Measured on a Linux x86-64 build machine (native engines): engine start-up to ready ≈ 10 ms,
typical XSLT run < 1 ms when the stylesheet is cached, ≈ 60 MB peak RSS on a 270 KB input,
executable 48 MB (Saxon 9.9) / 54 MB (Saxon 12), ≈ 16–18 MB compressed.

## Using it

### Desktop app

Download the installer for your platform from the CI artifacts (see *Building* below), install it,
open a project folder (any folder; **Init** adds `mappings/`, `schemas/`, `tests/`). Then:

* open a `.xsl` in the tree, enter input body / headers / properties on the left,
* the output updates live (or press **Ctrl+Enter**); compile and runtime errors are marked in the editor,
* **Parameters** shows how each `xsl:param` was bound (header, property, default),
* **Save as test** stores input, mapping reference and the current output as a regression test,
* **Tests** runs all test cases of the project on the right engine.

The installers are not code-signed yet: on macOS, right-click → *Open* the first time; on Windows,
SmartScreen asks once ("More info" → "Run anyway"). The Windows installer installs per user, no admin rights.

### Command line (CI-friendly)

```sh
cpiwb-xml xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
cpiwb-xml validate --xsd schemas/order.xsd --in output.xml
cpiwb-xml xpath --expr "//Item/@sku" --in input.xml --version 3.1
cpiwb-xml test examples/demo-project/tests        # exit code 1 if a case fails
```

### Browser mode (no installation of the desktop shell)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode runs one engine (one Saxon profile) and listens on localhost only.

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

* `step`: `xslt` or `xsd` (`schema` instead of `xslt`; expect `valid`, `errorContains`).
* Header/property types: `String`, `Integer`, `Long`, `Boolean`, `Double`, `Float`, `BigDecimal`, `Date` (ISO-8601), `byte[]` (Base64), `null`.
* `compare`: `xml` (ignores formatting and namespace declarations), `text`, `trimmed`.
* `expect.ok: false` asserts that the mapping fails.

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

Run the probes in `probes/` on a test tenant to turn the "to verify" items into facts.

## Building

Requirements: JDK 17+ and Maven; GraalVM 21+ for native engines; Node 22; Rust (stable);
on Linux the WebKitGTK development packages (see `.github/workflows/build.yml`).

```sh
mvn -f engine/pom.xml verify                         # engine + tests for both Saxon versions
GRAALVM_HOME=/path/to/graalvm engine/native/build-native.sh   # native engines → engine/native/out
python3 engine/native/workload.py engine/native/out/cpiwb-xml-he99 serve --stdio   # smoke test

cd app
npm ci
node scripts/prepare-sidecars.mjs                    # copy engines to src-tauri/binaries
npx tauri build                                      # installer for the current OS
npx tauri dev                                        # development (UI hot reload)
```

`.github/workflows/build.yml` builds Windows (.msi/.exe), macOS (Apple Silicon and Intel, .dmg)
and Linux bundles. Native images must be built on each target OS; there is no cross-compilation.

When a Saxon feature fails only in the native build, re-record the reachability metadata:
run `workload.py` with `-agentlib:native-image-agent=config-merge-dir=engine/xml-heXX/src/main/resources/META-INF/native-image/io.cpiwb/cpiwb-xml-heXX`
and regenerate `saxon-generated/reflect-config.json` with `engine/native/GenReflect.java`.

## Licences of bundled components

Saxon-HE: MPL-2.0 · Tauri, Monaco: MIT/Apache-2.0 · GraalVM Native Image runtime: GPLv2 with
Classpath Exception. No SAP code or jars are included.
