---
title: "Loading a project's platform files can outlive the project, so each load stops once a newer load or a close came"
author: Claude
date: 2026-11-14
branch: claude/nice-turing-nql3ja
cost: { minutes: 8, tokens: 54012 }
id: l-b73abd9f
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
  - app/src/lib/paths.ts
  - app/e2e/groovy.mjs
---

## Decided
- Found in the double check of W1–W3: the Platform tables read the four files one by one with awaits. A project closed (or another opened) during that read got the old project's later files written into its tables afterwards; Save could then write them into the new project's files. A generation counter now stops a load once a newer load or a reset started, or the project changed.
- A test case's attachment path above the project root keeps its leading `..` (projectPath), so the engine refuses it as outside the project; joinPath dropped the `..` and pointed at another file inside the project.
- "↻ Reload files" really reloads (after a question when tables have unsaved changes); saving a platform file in the File tab while its table has unsaved changes says so.

## Tried and ruled out
- Treating "open another project while one is open" as a path to guard in open(): the UI only switches projects after Close project, which already asks and resets. The guard stays as a safeguard, but it was no reachable bug.

## Watch out
- Every async loader of per-project state needs the same check after each await; the e2e only caught this one because it closes right after opening.

In the code: loadPlatform / resetPlatform / platformGen in app/src/lib/store.svelte.ts; projectPath in app/src/lib/paths.ts; e2e step 4a1b in app/e2e/groovy.mjs.
