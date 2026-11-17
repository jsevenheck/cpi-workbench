---
title: "Closing a flow step taken from an iFlow brings back the tab's own sample settings, not empty ones"
author: Jona
date: 2026-11-17
branch: claude/great-cannon-v2nd6h
cost: { minutes: 29, tokens: 90808 }
id: l-5e29be58
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/e2e/groovy.mjs
  - app/src/components/Sidebar.svelte
  - app/src/lib/paths.ts
  - app/src/lib/store.svelte.ts
  - app/tests/paths.test.mts
  - docs/user-guide.md
  - app/src/components/FlowStepsView.svelte
  - docs/manual-test.md
---

## Decided
* The user asked (2026-11-17) for an × to close a Router, Content Modifier etc. that was opened from an imported iFlow. The × sits after the "From <iFlow> · <step>" line and gives that one step the tab's own starting settings back, drops its result and invalidates a run under way; the other steps keep their iFlow settings. Gather keeps its messages (they come from a splitter, not the iFlow).
* Unlike the × of an editor (which leaves an empty editor, not the sample), a flow step goes back to its samples: an empty Router, Encoder or Digest has no meaningful state, and the samples are what the tab shows before any import.
* No question before closing: the settings were never saveable, and the iFlow is untouched, so the step opens again from the iFlow list or the diagram.
* Closing the project, or opening another one, closes all steps taken from its iFlows; before, their settings and "From" line stayed after the project was gone.

## Watch out
* The starting settings live in one function, which builds both the initial state and the reset. The MCP server repeats some of them (encoder, decoder, digest, converter defaults in its flow module); change both together.

In the code: flowStepDefaults, closeFlowStep, closeIflowFlowSteps (closeProject, open) in app/src/lib/store.svelte.ts; button "Close iFlow step" in app/src/components/FlowStepsView.svelte; e2e in app/e2e/groovy.mjs step 4b and after Close project.
