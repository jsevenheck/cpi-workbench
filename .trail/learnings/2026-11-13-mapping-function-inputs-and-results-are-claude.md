---
title: "Mapping function inputs and results are labelled as the source field's values per context, because a bare value dropdown read as a type picker"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 2, tokens: 21345 }
id: l-320adb08
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/e2e/groovy.mjs
  - app/src/components/GroovyView.svelte
  - app/src/components/QueueEditor.svelte
  - docs/user-guide.md
  - app/src/lib/functions.ts
  - app/src/lib/store.svelte.ts
  - app/tests/functions.test.mts
  - docs/manual-test.md
  - docs/status.md
---

## Decided
- The user could not tell what the String[] argument editor meant: a per-value dropdown showing "value" looked like choosing an element type. Both sides now say what they are: the input boxes are the source field's values as the mapping passes them in, one per context; the choice per value reads text / SUPPRESS / xsi:nil / null and stays visually quiet for plain text; Result lists the output queue as "Context 1, 2, …" rows so each result lines up with its input box.

## Watch out
- The JSON form of the output queue is inside a collapsed "Text" section, so e2e checks read the queue from the table's data-queue attribute, not from the pre.

In the code: QueueEditor.svelte (intro, select.plain); GroovyView.svelte (.queue-out table, showEntry); waitQueue helper in app/e2e/groovy.mjs.
