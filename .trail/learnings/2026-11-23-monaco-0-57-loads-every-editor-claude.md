---
title: "Monaco 0.57 loads every editor feature up front, TypeScript stays on 6 until svelte-check supports 7"
author: Claude
date: 2026-11-23
branch: claude/magical-dirac-dcu53h
cost: { minutes: 30, tokens: 455539 }
id: l-000204aa
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/src/lib/monaco.ts
  - app/package.json
  - app/src-tauri/Cargo.lock
  - app/package-lock.json
  - app/src-tauri/Cargo.toml
  - app/vite.config.ts
  - engine/core/src/test/java/io/cpiwb/core/CoreTest.java
  - app/e2e/groovy.mjs
  - app/src/lib/engine.ts
---

## Decided
- Monaco 0.56 reorganized its ESM entry points; the app imports `monaco-editor/editor`, `languages/definitions/<lang>/register`, `languages/features/json/register` and `features/register.all`. The owner chose (2026-11-23) the full feature set in every editor over a worker shim or staying on 0.55.1.
- Why the full set: Monaco 0.57's JSON language feature imports its internal `internal/common/workers.js`, which registers every editor contribution when the first JSON model opens. A curated list would give editors created after that more features than earlier ones.
- TypeScript 6.0.3, not 7: svelte-check 4.7.8 declares the peer `typescript ^5 || ^6`, and TS 7 (the native port) ships no classic compiler API.
- The owner's rule for app dependency updates: take the newest stable release that is at least two weeks old. On 2026-11-23 the owner allowed an exception for patch releases that fix an advisory reported by `cargo audit` or `npm audit`, even when they are younger.
- 2026-11-24, the owner: the start screen offers Import iFlow… next to Open folder. Order: pick the zip, then a save dialog names the new project folder (default: the zip's name); the engine creates it, initializes it and the export is imported.
- The engine creates the folder (`project.open` with `create`), not the Tauri shell: browser mode needs the same path, and no fs plugin is needed. It uses createDirectory, so an existing folder (also an empty `dir`, which resolves to the engine's working directory) is refused, never initialized.
- A failed import leaves the new, initialized project open with the error; the app deletes nothing.
- With a project open, Import iFlow imports into that project as before; the MCP `import_iflow` still needs an open project.
- 2026-11-24, on the owner's Windows machine: Import iFlow into a new project failed with "Not a folder: <new path>". The XML engine jar predated `project.open`'s `create` option and ignored it, so it opened a folder that did not exist. `mvn -f engine/pom.xml package -DskipTests` alone fixed it.
- 2026-11-24, the owner: an empty Where used search was not intuitive. With an empty query and no kind, the tab shows the distinct addresses, credentials, queues, key aliases and files of the imported iFlows with their counts (a click searches the value as that kind), then each iFlow's parameters. The Search/Parameters switch is gone; ← Overview clears the search.
- Built from `iflow.index`, which already returns every classified entry and the parameters in one call; the engine is unchanged.

## Tried and ruled out
- `features/suggest/register` alone for completions: it only registers inline completions; the suggest widget needs `editor/contrib/suggest/browser/suggestController` (the Groovy e2e checks the widget).
- A Vite alias replacing `internal/common/workers.js` with an own `createWebWorker`: keeps the old slim set, but depends on Monaco's internal worker handshake (`postMessage('ignore')`, then createData).
- `npm audit` reports DOMPurify advisories via monaco-editor 0.57 (exact pin 3.4.15). Monaco bundles its own copy in `esm/vs/base/browser/dompurify/`, so an npm override would only silence the report; its sanitizer uses RETURN_DOM_FRAGMENT, not the IN_PLACE / SAFE_FOR_TEMPLATES / xhtml modes the advisories name (checked by grep, an assumption beyond that).
- Keeping the advisory-affected versions until the two weeks pass: rejected because those versions were already on main, so following the rule would have kept known vulnerabilities in the lockfile.
- A folder picker where the user clicks "New folder": picking an existing folder (Downloads) by mistake would initialize and import there.
- Picking a parent folder and naming the subfolder after the iFlow: two dialogs or a name the user does not see.

In the code: ProjectFiles.open (`create`), pickNewFolder in app/src/lib/engine.ts (dialog:allow-save in capabilities), importIntoNewProject in store.svelte.ts, Sidebar importZip, e2e step 8 in groovy.mjs
- Listing every setting of every iFlow by default: thousands of rows for a package, the opposite of intuitive.
- Opening on the Parameters view: one line, but the search stays as hard to discover.

## Watch out
- When updating Monaco, diff `features/register.all.js` against the imports of `internal/common/workers.js` and keep the extra deep imports in `app/src/lib/monaco.ts` in line.
- Cargo has no publish-date filter (npm has `--before`). The two-week rule for the lockfile was applied with a script: after `cargo update`, query crates.io per locked crate and `cargo update -p name@ver --precise <newest older>`; versions with `+spec-…` build metadata contain a hyphen that is not a pre-release.

In the code: app/src/lib/monaco.ts, app/package.json, app/src-tauri/Cargo.lock
- Name each exception and its advisory ID in the commit message so the next update can tell it apart from an accidental bump.

In the code: app/src-tauri/Cargo.lock (url 2.5.9 for RUSTSEC-2026-0592, cookie 0.18.3 for RUSTSEC-2026-0594)
- An engine older than the UI accepts unknown params silently. A new RPC option then fails with an unrelated message; first rebuild the jars (or check which engine binary runs).
- `engine::locate` checks the folder of the app exe first (`app/src-tauri/target/debug/cpiwb-xml-*.exe`, the sidecar copies), then `engine/native/out`, then the XML jars. Stale native engines in either place win over fresh jars.

In the code: app/src-tauri/src/engine.rs `locate`
- The index is read when the tab mounts and again after a rescan or import (loadIflowModels); each read parses all iFlow models, there is no cache.

In the code: inventory in app/src/lib/whereused.ts, app/src/components/WhereUsedView.svelte, e2e 4b3 and 6b in groovy.mjs
