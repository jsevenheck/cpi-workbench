# AGENTS.md

CPI Workbench simulates SAP Integration Suite (Cloud Integration) steps locally:
* XSLT Mapping on Saxon-HE 9.9 and 12, as GraalVM native images.
* Groovy Script 1.x (Groovy 2.4.21) and 2.x (Groovy 4.0.29), both on Camel 3.14.7 and Java 21, as a tenant runs them.
* XSD validation and XPath.
* Flow Steps: Content Modifier, Filter, Router, General / Iterating Splitter, Gather and Converter (Camel Simple on
  the Groovy engines' Camel; XPath, filtering, splitting, gathering and JSON / XML / CSV conversion on the XML engines).
* Message Mapping functions (Groovy custom functions with `MappingContext` and `Output`), run on their own.

The UI is Tauri 2 + Svelte 5 + Monaco. Everything runs offline; the app never connects to a tenant.

## Commands

```sh
mvn -f engine/pom.xml verify                    # all engine tests (Java 17+, Maven; a tenant and the app run 21)
mvn -f engine/pom.xml package -DskipTests       # jars only
mvn -f engine/pom.xml package -pl groovy-v1,groovy-v2 -am   # one module and its dependencies
java -jar engine/xml-he99/target/cpiwb-xml-he99.jar test testdata/regression-project/tests     # regression cases
java -jar engine/xml-he12/target/cpiwb-xml-he12.jar test testdata/regression-project/tests
java -jar engine/groovy-v1/target/cpiwb-groovy-v1.jar test testdata/regression-project/tests
java -jar engine/groovy-v2/target/cpiwb-groovy-v2.jar test testdata/regression-project/tests
npm --prefix app run check                      # Svelte/TypeScript checks
npm --prefix app test                           # UI unit tests (formatters; Node 22.18+)
npm --prefix app run build                      # UI bundle for browser mode and e2e
CPIWB_REQUIRE_ENGINES=1 cargo test --manifest-path app/src-tauri/Cargo.toml   # shell ⇄ engines over stdio, MCP server (needs the UI bundle, engine builds; JAVA_HOME picks the JRE)
node app/scripts/mcp-smoke.mjs app/src-tauri/target/debug/cpi-workbench testdata/regression-project   # MCP server as a client starts it
```

The end-to-end browser tests (`app/e2e/*.mjs`) need running engines; the steps are in `docs/building.md`.
Native images (`engine/native/build-native.sh`) need GraalVM and are usually left to CI
(`.github/workflows/build.yml`). A pushed tag `v<version>` publishes a GitHub release; the steps are
in `docs/building.md` (*Releasing*).

## Layout

* `engine/core`: JSON-RPC, project files and path checks (`ProjectPaths`), test runner, iFlow import.
* `engine/xml*`: XSLT, XSD, XPath, the XPath flow steps and the converters, against both Saxon versions.
* `engine/groovy*`: the script runner and the clean-room `com.sap.*` API.
  * `src/api-common`: the Script API v1 and the platform services shared by both versions.
  * `src/api-v2`: the Script API v2.
  * `groovy-v1`, `groovy-v2`: the two builds and their entry points.
* `app/src`: the UI. `app/src-tauri`: the Rust shell; `src/mcp/` is the MCP server (`cpi-workbench mcp`).
  `mcp/flow.rs` repeats the Flow Steps tab's calls (`app/src/lib/store.svelte.ts`), including the converter
  defaults; change both together.
* `examples/demo-project`: the sample project users open in the app (no test cases).
* `testdata/regression-project`: the project with test cases that CI, the e2e and the shell tests run.
* `probes`: tenant probes, the simulator's baseline output and a tenant's results (`results-tenant/`).

## Rules

* **No SAP code or jars.** The `com.sap.*` classes are clean-room implementations of SAP's
  published signatures and documentation. Never copy SAP sources, decompile SAP binaries, or add
  SAP artifacts as dependencies.
* **Fidelity claims need evidence.** Behaviour that imitates CPI follows a source:
  * the finding (F…), question (Q…) and source (S…) ledger in `docs/plan.md`,
  * SAP's docs and published interfaces,
  * Camel's or Saxon's behaviour.

  Label assumptions as assumptions, in code comments and docs. Do not present unverified behaviour as "like CPI".
* **When behaviour changes:**
  * Update the tests.
  * Update `docs/fidelity.md` and `docs/status.md`.
  * Add a line under *Unreleased* in `CHANGELOG.md` for changes users notice.
  * If a probe's output changes, update the probe and its baseline in `probes/baseline-simulator/`.
* **Test-only secrets** in `platform/` files, test resources and examples. Never use real tenant credentials or keys.
* Code, comments, commit messages and docs are in English. Match the surrounding code style.
  Keep the README short; details belong in `docs/`.
* Simulator limits surface as diagnostics or as exceptions whose message starts with
  `UnsupportedInSimulator`. Never fail silently.

## Documentation

`docs/` holds the user guide, test-case format, fidelity table, status audit, architecture,
build steps and the research plan. **Read `docs/` only when the task needs it.** Start at
`docs/README.md`, which is a one-line-per-page index, and open just the page you need.
