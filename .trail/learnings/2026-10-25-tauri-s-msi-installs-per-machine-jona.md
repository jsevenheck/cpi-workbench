---
title: "Tauri's MSI installs per machine; only the NSIS setup.exe honours installMode currentUser"
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 12, tokens: 117607 }
id: l-48689371
scope: repo
status: active
touches:
  - app/e2e/smoke.mjs
  - app/src/lib/paths.ts
  - app/src/lib/store.svelte.ts
  - app/tests/paths.test.mts
  - docs/fidelity.md
  - docs/status.md
  - engine/native/workload.py
  - engine/xml/src/main/java/io/cpiwb/xml/ProjectResolver.java
  - engine/xml/src/saxon12/java/io/cpiwb/xml/compat/SaxonCompat.java
  - engine/xml/src/saxon99/java/io/cpiwb/xml/compat/SaxonCompat.java
---

## Decided
- `bundle.windows.nsis.installMode: currentUser` in `app/src-tauri/tauri.conf.json` affects only the NSIS `setup.exe`. Tauri's WiX template uses `InstallScope="perMachine"`, so the `.msi` needs admin rights. `docs/install.md` and `docs/user-guide.md` say so; do not describe the MSI as per-user.
- Third-party licence texts live in `licenses/` (with `licenses/README.md` as the list). The parent `engine/pom.xml` copies them into every jar under `META-INF/third-party/`; `app/scripts/prepare-sidecars.mjs` copies them into the installers and generates `THIRD-PARTY-LICENSES-desktop.txt` (Rust crates + npm production deps) with `app/scripts/third-party-licenses.mjs`.

## Watch out
- When a bundled dependency changes version (Saxon, Groovy, Camel, JRE, GraalVM), update `licenses/` and its README with the notices from that version's distribution.
- `app/scripts/set-version.mjs` also sets the poms and `XmlEngine.VERSION` / `GroovyEngine.VERSION`; the release job fails if the engines do not match the tag.
