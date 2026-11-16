---
title: An external review found async and boundary bugs the tests could not see; each fix has a test that fails without it
author: Claude
date: 2026-11-14
branch: claude/nice-turing-nql3ja
cost: { minutes: 17, tokens: 770804 }
id: l-b8d7701e
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
---

## Decided
- A review of 94296b4 (12 findings) was checked finding by finding against the code; all 12 held. Each fix got a test that was run against the code without the fix and failed there: MCP parallel save_file (both calls wrote), Router throw with a runId, late platform save after a project switch (table emptied), XPath result back after its input closed, mapping arguments dropped after the first run of a new function.
- Answer-side races: anything that awaits an engine call and then writes per-project or per-input state must check a generation (platformGen, xpathSeq, xsdSeq, flowSeq) after the await. Close actions bump the generation.
- MCP: the run's input is resolved once before the run and passed on under an internal key, so the step and the kept result see the same message; save_file/import_iflow hold one lock over "exists?" and the write, since requests run on parallel threads.
- Numbers beyond 2^53 cross into the engine as text (the engine's Json.num reads strings); absolute attachment paths stay absolute so the engine refuses them; documents with entity references in the content get no XPath marks (the scanner cannot see entity elements).

## Tried and ruled out
- Holding the request (not the answer) in the late-save e2e: the write then reached the engine after the project switch and landed in the other project, a scenario the app cannot produce. Hold only the answer (route.fetch, then fulfill later).

## Watch out
- Two pre-existing engine bugs from the review were fixed with it: DOM output of an <html> root switched to the HTML method (now METHOD xml), and XPath 1.0 lacked the xml prefix.

In the code: savePlatform, closeInput, closeEditorFile in app/src/lib/store.svelte.ts; RESOLVED_INPUT and write_lock in app/src-tauri/src/mcp; router ok in mcp/flow.rs; read_chars in mcp/files.rs; e2e 4a1b/4f in groovy.mjs and the held XPath run in smoke.mjs.
