---
title: "The CPI Helper receiver's default port moved to 7790; a saved 7778 is left alone because the app cannot change the plugins' Custom URL"
author: Claude
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 9, tokens: 213529 }
id: l-460c0a5b
scope: repo
status: active
src: agent
touches:
  - app/src/lib/share.ts
  - app/src/lib/receiver.svelte.ts
  - app/tests/share.test.mts
  - docs/user-guide.md
  - docs/architecture.md
  - docs/manual-test.md
  - docs/plan.md
---

## Decided
- The receiver's default was 7778 in 0.4.0. That is the Groovy engines' own `--http` default and the Groovy 1.x port of the browser mode, the e2e tests and CI (7777 to 7780 are taken by the four engines), so the desktop app with the receiver on and a browser-mode Groovy 1.x engine could not run side by side. The user agreed to 7790 on 2026-11-16, the day 0.4.0 was released (no downloads yet).
- No migration code. The setting is stored with its port every time the user switches it, so whoever turned the receiver on in 0.4.0 keeps 7778 and the Custom URL in GroovyDebugX and xsltDebugX keeps working. Only an installation that never touched the setting gets 7790.

## Tried and ruled out
- Rewriting a saved 7778 to the new default: the hand-over is one way, the app cannot change the Custom URL in the extension, so the one-click path would break without a word. A saved 7778 also cannot be told apart from one the user chose.
- Moving the engines' ports instead: more places (the Java default, the browser mode's address, e2e, CI, docs) and the older convention.
- A hint under the settings while the port is 7778: the bind error already says "Port 7778 cannot be used … Choose another port" when the conflict happens.

## Watch out
- Whether other common software uses 7790 was not checked.
- A unit test cannot import a `.svelte.ts` file (runes), so the constant lives in plain TypeScript and the test also checks that the user guide names the same port.

In the code: `RECEIVER_DEFAULT_PORT` in `app/src/lib/share.ts`, used by `app/src/lib/receiver.svelte.ts` (key `cpiwb.receiver` in localStorage); test in `app/tests/share.test.mts`; `docs/user-guide.md`, `docs/architecture.md`, `docs/manual-test.md` 2.11j, `docs/plan.md` W2b.
