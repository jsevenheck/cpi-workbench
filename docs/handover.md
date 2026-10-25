# Handover: continue on a local Windows machine (2026-10-25)

Paste the prompt below into a new Claude Code session opened in the repository root on Windows.

---

You are continuing work on CPI Workbench. Read `AGENTS.md` first and follow its rules: no SAP code or
jars, fidelity claims need evidence, test-only secrets, English in code and docs. Read `docs/` only
as needed, starting at `docs/README.md`. Answer me in German.

## State

* Branch `main` holds everything. Commit and push there. The old branch `ccr-a0737ec8-9kugvv` is still
  the GitHub default; I will switch the default to `main` and delete the old branch myself. PR #1 is
  not from you; leave it alone unless I ask.
* The last full CI run, on Linux, Windows and macOS, was green at `d237e25`. That covers engine tests,
  browser e2e, and shell tests against the native engines and the trimmed JRE.
* The repository is private, so Actions minutes count: Windows 2x, macOS 10x. The runs after
  `d237e25` did not start, probably because the monthly quota ran out (unverified). CI now runs
  Linux only on pushes and PRs to `main`; tags `v*` and manual runs (`workflow_dispatch`) run all
  three systems. So **test on Windows locally** before you push.
* Open items and percentages per feature are in `docs/status.md`.

## First task: manual Windows test of the desktop app

CI cannot check the installer or the Tauri window (WebView2). Do this with me:

1. Prerequisites. Check with me what is missing and help me install it:
   * JDK 17 with `JAVA_HOME` set, Maven, Node 22, Rust (stable, MSVC toolchain).
   * Git Bash for the `.sh` scripts.
   * The WebView2 runtime, which ships with Windows 11.
   * GraalVM and the MSVC build tools are only needed for the native engines. Skip them at first:
     the shell falls back to the XML engine jars and runs them with `JAVA_HOME`.
2. Build and test the engines: `mvn -f engine/pom.xml verify`, then the three demo-case commands
   from `AGENTS.md`.
3. Browser e2e: follow `docs/building.md` → "End-to-end tests". Use a forward-slash temp path for
   the demo copy, e.g. `C:/Users/<me>/AppData/Local/Temp/demo-project`. Playwright:
   `npm --prefix app install --no-save playwright && npx --prefix app playwright install chromium`.
4. Shell tests: `npm --prefix app run build`, then
   `CPIWB_REQUIRE_ENGINES=1 cargo test --manifest-path app/src-tauri/Cargo.toml`.
5. Desktop window: `cd app && npx tauri dev`. Check that:
   * the window opens,
   * the project `examples/demo-project` opens,
   * an XSLT case, a Groovy case and an XSD case open and run,
   * "Run all tests" shows 0 failed,
   * "Import iFlow" works with a zip, if I have one.
6. Installer: either download the artifact `cpi-workbench-windows-latest` from the green CI run
   (https://github.com/jsevenheck/integration-suite-tools/actions/runs/40185297713) or build it
   locally with `npx tauri build`. That needs the sidecars (`docs/building.md`), so it also needs
   GraalVM for the native engines. Then:
   * install it (SmartScreen warns, because the installer is not signed),
   * start the app and repeat the checks from step 5,
   * uninstall it.
7. Record what you found:
   * fix bugs, with a test where possible,
   * update `docs/status.md` (row "Desktop app and browser mode", "Next steps"),
   * commit and push to `main`.

## Next candidates after that (ask me which)

* Attachments and SOAP headers are edited only in `case.json`, not in the app (`docs/status.md`,
  "Test cases, runner, CLI").
* Distribution: code signing and auto-update (Phase 5 in `docs/plan.md`).
* Run the tenant probes (`probes/README.md`) once I have access to a test tenant.

## Pitfalls already met on Windows

* URLClassLoader locks jar files. The Groovy engine therefore loads copies of `lib/` jars from a
  temp folder (`ScriptRunner.shadowCopy`).
* `.gitattributes` forces LF line endings. Keep it that way: the test cases compare text.
* e2e tests: Monaco's overview ruler can take a click in the middle of the editor, so click near the
  top left. A result badge from the previous run stays visible while the next run is going, so
  wait for the expected content instead.
