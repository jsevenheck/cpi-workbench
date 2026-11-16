---
title: "CPI Helper hands a traced message over in a link with a custom address, so the app reads the link and connects to nothing"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 8, tokens: 81131 }
id: l-512d0d40
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/src/lib/store.svelte.ts
  - app/tests/platform.test.mts
  - docs/status.md
  - engine/groovy/src/main/java/io/cpiwb/groovy/ScriptRunner.java
  - engine/groovy/src/test/java/io/cpiwb/groovy/ScriptRunnerTest.java
  - app/src/lib/share.ts
  - docs/plan.md
---

## Decided
- CPI Helper's GroovyDebugX and xsltDebugX plugins open a link in the browser and both have a "Custom URL" setting (read from their sources; undocumented). The data is JSON, raw DEFLATE, URL-safe Base64: GroovyDebugX appends it to the address, xsltDebugX writes `#share/<data>`. The app reads that link: Import… (paste) in the Groovy and XSLT tabs, and the browser mode directly at `…/#share/<data>`.
- `#share/` on purpose: what follows `#` is never sent to a server, so the message stays on the computer, and the app removes it from the address after reading.
- An imported script is someone's code: it arrives unsaved and unarmed, like an opened file, and runs only on Run. Parts the user did not send (empty body, no headers) leave the tab's values alone.
- Unpacking uses the web view's DecompressionStream, no new dependency.

## Tried and ruled out
- A `cpi-workbench://` link for the desktop app in this step: it needs Tauri's deep-link and single-instance plugins, installer registration and a check of link length limits on Windows and macOS, none of which can be tried in the cloud session. Planned as the open part of W2.
- Reading GroovyDebugX's "Contiva IDE" format (a zip inside gzip): it would need an unzip library; the import says to choose Custom URL instead.

## Watch out
- Not tried with the extension on a tenant: the tests build links the way the plugins' sources do.
- CPI Helper is GPL-3.0: the format was read, no code was copied.
- The plugins open a new browser tab for every hand-over.

In the code: app/src/lib/share.ts (tests in app/tests/share.test.mts); importMessage, importFromAddress in app/src/lib/store.svelte.ts; e2e step 12 in smoke.mjs and 4e in groovy.mjs; docs/plan.md §10.5 W2, Q-T2, S47.
