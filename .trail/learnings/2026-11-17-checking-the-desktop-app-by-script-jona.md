---
title: "Checking the desktop app by script: move stale native engines aside, and reach file pickers through the store module"
author: Jona
date: 2026-11-17
branch: claude/great-cannon-v2nd6h
cost: { minutes: 25, tokens: 226080 }
id: l-8aaf44b4
scope: repo
status: active
src: agent
touches:
  - app/src-tauri/src/engine.rs
  - app/src/App.svelte
  - docs/manual-test.md
  - app/src/components/FlowStepsView.svelte
  - app/e2e/groovy.mjs
  - app/src/components/Sidebar.svelte
  - engine/core/src/main/java/io/cpiwb/core/ProjectFiles.java
  - app/src/lib/store.svelte.ts
  - engine/core/src/main/java/io/cpiwb/core/IflowImport.java
  - engine/core/src/test/java/io/cpiwb/core/ArtifactImportTest.java
---

## Decided
* The pre-release desktop check on Windows (2026-11-17, branch claude/great-cannon-v2nd6h at 3ba1a43) drove the real WebView2 window over CDP for encoder/decoder, digest, diagram, import, history, examples and receiver. Native file pickers (Import iFlow, Open folder) cannot be clicked from Playwright; in `tauri dev` the Vite dev server serves the same module instance, so `await import('/src/lib/store.svelte.ts')` in `page.evaluate` reaches `importIflow(zip)`, `open(dir)` and `useAsInput(path)` and tests everything after the picker.

## Watch out
* `engine::locate` prefers `engine/native/out/*.exe` over the XML jars, for `cargo test` and `tauri dev` alike. Images built before the last `engine/core` commit (here: 16.11. 15:41, before 8c7fb30/9174da5/3ba1a43) silently test old import code. Compare their dates with `git log -- engine/` and move the folder aside (do not delete) before testing.
* The receiver's saved port is applied about a second after the status says "engine(s) available" (`initReceiver` runs after `init()` and the address import). A check that opens ⚙ right away sees the default 7790 even with a saved 7778.
* On Windows `Shared.zip` and `shared.zip` are one file; put the case variant in another folder to test the import's case check.
* The XML engine reports the digest filter's node count; `/Order/Item` selects 0 nodes in the demo `order.xml` (`/Order/Items/Item` gives 2).

In the code: app/src-tauri/src/engine.rs `locate`; app/src/App.svelte onMount; WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9223.
