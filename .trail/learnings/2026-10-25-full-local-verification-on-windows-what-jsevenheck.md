---
title: "Full local verification on Windows: what to set up before the checks"
author: Jsevenheck
date: 2026-10-25
branch: main
cost: { minutes: 4, tokens: 61635 }
touches:
  - engine/pom.xml
  - docs/building.md
---

## Decided
* A full local check of the repo is: `mvn -f engine/pom.xml verify`, the four engine jars' `test testdata/regression-project/tests`, `npm --prefix app ci` then `run check`, `test`, `run build`, and `cargo test` with `CPIWB_REQUIRE_ENGINES=1` plus the TAURI_CONFIG override from docs/building.md. CI status of the public repo can be read with curl from api.github.com when `gh` is not installed.

## Tried and ruled out
* Running `npm --prefix app run check` / `run build` on a fresh clone: fails with "svelte-check/vite ist entweder falsch geschrieben..." because `app/node_modules` is missing, not because of a code problem. Run `npm --prefix app ci` first. `npm test` passes without it (node --test only).

## Watch out
* Regression cases are spread over engines: each jar runs only its own profiles and skips the rest (xml-he99 3 passed, xml-he12 2, groovy-v1 2, groovy-v2 1, the rest SKIPPED). Skips are expected; failures are not.
* A local `trail` older than the one that wrote `.trail/hook.cjs` (0.23 vs 0.25 on 2026-10-25) rewrites the hook to an older version and shows it as a modified file. Upgrade trail or `git checkout .trail/hook.cjs`; do not commit the downgrade.
* `app/e2e/smoke.mjs`: after *Save as…* the file tree reloads and the copy takes the original's row. Clicking a tree entry before the reload finishes lands on the wrong file (failed every time locally on Windows; most likely also the Linux CI failure on 12bb1a4, whose log was not read). Wait for the new tree entry before clicking.
* CI's macOS runner is `macos-26`. Without `MACOSX_DEPLOYMENT_TARGET`, clang targets the runner's macOS, so native engines would need macOS 26 while `tauri.conf.json` promises 11.0. The desktop job sets it and checks `minos` with otool.
