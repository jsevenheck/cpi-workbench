---
title: CI runs all three OSes on push again; local Tauri builds need a config override without GraalVM
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 1, tokens: 1379 }
id: l-223bfa18
scope: repo
status: active
touches:
  - ".github/workflows/build.yml"
  - docs/building.md
  - app/src-tauri/tauri.conf.json
  - "app/src-tauri/src/engine.rs#locate"
---

## Decided
* `.github/workflows/build.yml` runs Linux, Windows and macOS again on pushes and PRs to `main`. The repo became public on 2026-10-25, so standard-runner minutes are free. The `branches: [main]` / `tags: ["v*"]` filter stays, so a push to main does not run a second time for an open PR.

## Tried and ruled out
* Linux-only CI on push (commit d299aef): it only made sense while the repo was private and Windows/macOS minutes cost 2x/10x.

## Watch out
* Without GraalVM, `cargo test` and `npx tauri dev` fail in tauri-build ("resource path binaries\cpiwb-xml-he99-... doesn't exist"), because `externalBin` and `resources` in `tauri.conf.json` must exist. Placeholder files are not a fix: tauri-build copies externalBin next to the dev exe, and `engine::locate` prefers that copy over the jar fallback. Use the override `{"bundle":{"externalBin":null,"resources":null}}` instead, via TAURI_CONFIG for cargo and `--config` for the tauri CLI (docs/building.md, "Without GraalVM").
* The e2e scripts write into the demo project. Give `smoke.mjs` and `groovy.mjs` a fresh copy each, as CI does, or groovy.mjs fails with "7 passed" instead of the 6 it expects.
