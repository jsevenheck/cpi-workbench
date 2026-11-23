---
title: "Monaco 0.57 loads every editor feature up front, TypeScript stays on 6 until svelte-check supports 7"
author: Claude
date: 2026-11-23
branch: claude/magical-dirac-dcu53h
cost: { minutes: 18, tokens: 134680 }
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
---

## Decided
- Monaco 0.56 reorganized its ESM entry points; the app imports `monaco-editor/editor`, `languages/definitions/<lang>/register`, `languages/features/json/register` and `features/register.all`. The owner chose (2026-11-23) the full feature set in every editor over a worker shim or staying on 0.55.1.
- Why the full set: Monaco 0.57's JSON language feature imports its internal `internal/common/workers.js`, which registers every editor contribution when the first JSON model opens. A curated list would give editors created after that more features than earlier ones.
- TypeScript 6.0.3, not 7: svelte-check 4.7.8 declares the peer `typescript ^5 || ^6`, and TS 7 (the native port) ships no classic compiler API.
- The owner's rule for app dependency updates: take the newest stable release that is at least two weeks old. On 2026-11-23 the owner allowed an exception for patch releases that fix an advisory reported by `cargo audit` or `npm audit`, even when they are younger.

## Tried and ruled out
- `features/suggest/register` alone for completions: it only registers inline completions; the suggest widget needs `editor/contrib/suggest/browser/suggestController` (the Groovy e2e checks the widget).
- A Vite alias replacing `internal/common/workers.js` with an own `createWebWorker`: keeps the old slim set, but depends on Monaco's internal worker handshake (`postMessage('ignore')`, then createData).
- `npm audit` reports DOMPurify advisories via monaco-editor 0.57 (exact pin 3.4.15). Monaco bundles its own copy in `esm/vs/base/browser/dompurify/`, so an npm override would only silence the report; its sanitizer uses RETURN_DOM_FRAGMENT, not the IN_PLACE / SAFE_FOR_TEMPLATES / xhtml modes the advisories name (checked by grep, an assumption beyond that).
- Keeping the advisory-affected versions until the two weeks pass: rejected because those versions were already on main, so following the rule would have kept known vulnerabilities in the lockfile.

## Watch out
- When updating Monaco, diff `features/register.all.js` against the imports of `internal/common/workers.js` and keep the extra deep imports in `app/src/lib/monaco.ts` in line.
- Cargo has no publish-date filter (npm has `--before`). The two-week rule for the lockfile was applied with a script: after `cargo update`, query crates.io per locked crate and `cargo update -p name@ver --precise <newest older>`; versions with `+spec-…` build metadata contain a hyphen that is not a pre-release.

In the code: app/src/lib/monaco.ts, app/package.json, app/src-tauri/Cargo.lock
- Name each exception and its advisory ID in the commit message so the next update can tell it apart from an accidental bump.

In the code: app/src-tauri/Cargo.lock (url 2.5.9 for RUSTSEC-2026-0592, cookie 0.18.3 for RUSTSEC-2026-0594)
