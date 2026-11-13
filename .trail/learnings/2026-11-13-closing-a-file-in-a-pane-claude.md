---
title: "Closing a file in a pane leaves an empty editor, not the sample, and never touches the file on disk"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 15, tokens: 128782 }
id: l-dc2b7f82
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
  - app/src/app.css
---

## Decided
- The × next to a file name closes the open file only: the pane stays, the file on disk is not written. Unsaved changes are asked about first (same question as opening another file).
- A closed mapping/script/schema leaves an EMPTY editor without a path (not the startup sample), so Save asks for a new path and cannot overwrite the closed file. The pane title says "No mapping/script open" and an overlay hint is shown.
- Closing a test case's file closes the whole case: its input body, headers, properties and (Groovy) attachments/extras go too, since they came with the case. Closing a plain script remembers its function and arguments like opening another script does.
- Closing an input empties the body and drops the file name; headers and properties stay (they do not come from the file).
- Live runs and run-on-tab-switch skip an empty mapping or script, so a closed editor does not produce a compile error.
- The user agreed to this design before it was built (2026-11-13).

## Watch out
- A close must invalidate a run still under way (runSeq++ for XSLT, forgetGroovyRun for Groovy), or the old result lands in the empty pane.
- The smoke e2e's "+ Script" step raced the file-list reload; it now waits for the new file in the tree first.

In the code: closeEditorFile / closeInput in app/src/lib/store.svelte.ts; autoRunXslt / autoRunGroovy skip empty text; button.close-file and .empty-hint in app/src/app.css; e2e steps 8b (smoke.mjs) and 4a3 (groovy.mjs).
