---
title: "The CPI Helper receiver's default port moved to 7790; a saved 7778 is left alone because the app cannot change the plugins' Custom URL"
author: Claude
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 21, tokens: 344191 }
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
  - CHANGELOG.md
  - docs/test-cases.md
  - app/scripts/mcp-smoke.mjs
---

## Decided
- The receiver's default was 7778 in 0.4.0. That is the Groovy engines' own `--http` default and the Groovy 1.x port of the browser mode, the e2e tests and CI (7777 to 7780 are taken by the four engines), so the desktop app with the receiver on and a browser-mode Groovy 1.x engine could not run side by side. The user agreed to 7790 on 2026-11-16, the day 0.4.0 was released (no downloads yet).
- No migration code. The setting is stored with its port every time the user switches it, so whoever turned the receiver on in 0.4.0 keeps 7778 and the Custom URL in GroovyDebugX and xsltDebugX keeps working. Only an installation that never touched the setting gets 7790.
- `test <path> --junit <file>` writes one `testsuite` per engine run. The user chose the engine's profile as each case's `classname` (2026-11-16), so the four engines' reports stay apart when a CI system merges them. Output and exit code do not change; a report that cannot be written is exit code 1, `--junit` without a file is 2.
- JUnit XML has no official schema. The layout is Maven Surefire's (root `testsuite`), because its schema is published and can be checked: reports from all four engines, with failed, broken and skipped cases, validate against `surefire-test-report.xsd` with xmllint.
- The XML is written by hand, not through the DOM helper: a message may quote any input, and characters XML 1.0 cannot hold (control characters, a lone surrogate) must become U+FFFD first, which the JDK serializer does not do. It also needs nothing new in the native images.
- All checks before a push ran in a Linux cloud container on 2026-11-16 (Java 21, Node 22, cargo present): `mvn verify`, the regression cases on the four jars, `npm ci` then check, test and build, both browser e2e tests, `cargo test` with the TAURI_CONFIG override, and the MCP smoke test.

## Tried and ruled out
- Rewriting a saved 7778 to the new default: the hand-over is one way, the app cannot change the Custom URL in the extension, so the one-click path would break without a word. A saved 7778 also cannot be told apart from one the user chose.
- Moving the engines' ports instead: more places (the Java default, the browser mode's address, e2e, CI, docs) and the older convention.
- A hint under the settings while the port is 7778: the bind error already says "Port 7778 cannot be used … Choose another port" when the conflict happens.
- Using the option in CI (`build.yml`): the user said not for now.
- The case's folder as `classname`: the user preferred the profile.

## Watch out
- Whether other common software uses 7790 was not checked.
- A unit test cannot import a `.svelte.ts` file (runes), so the constant lives in plain TypeScript and the test also checks that the user guide names the same port.

In the code: `RECEIVER_DEFAULT_PORT` in `app/src/lib/share.ts`, used by `app/src/lib/receiver.svelte.ts` (key `cpiwb.receiver` in localStorage); test in `app/tests/share.test.mts`; `docs/user-guide.md`, `docs/architecture.md`, `docs/manual-test.md` 2.11j, `docs/plan.md` W2b.
- No CI reporter (GitHub Actions, Jenkins, GitLab) has read a report yet; the docs say so. Do not write "read by" until one has.
- The text and JSON summary still count an error under "failed"; only the report separates `failures` and `errors`.
- The cases run before `--junit` without a file is rejected.

In the code: `Cli.testCommand`, `Cli.junitXml`, `Cli.xml` in `engine/core`; both `Main` classes call `testCommand`; tests in `CoreTest`; `docs/test-cases.md`, `docs/plan.md` W5. Schema: https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd
- `cargo test` fails in the build of `gdk-sys` until Tauri's Linux libraries are installed: `apt-get install -y libgtk-3-dev libwebkit2gtk-4.1-dev libsoup-3.0-dev libjavascriptcoregtk-4.1-dev librsvg2-dev libayatana-appindicator3-dev`.
- `cargo test` builds no `target/debug/cpi-workbench`. Run `cargo build` with the same TAURI_CONFIG override before `node app/scripts/mcp-smoke.mjs`.
- Playwright is already installed in the container and the e2e scripts find it through NODE_PATH; do not install it again.
- Stop the four `serve --http 777x` engines after the e2e tests, or the next start fails to bind.

In the code: `docs/building.md` (Shell tests, End-to-end tests), `app/e2e/*.mjs`, `app/scripts/mcp-smoke.mjs`.
