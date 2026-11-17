---
title: The app starts empty and a closed iFlow flow step goes back to empty settings; examples are the way to try it without a project
author: Jona
date: 2026-11-17
branch: claude/great-cannon-v2nd6h
cost: { minutes: 11, tokens: 87026 }
id: l-325a0be9
scope: repo
status: active
replaces: l-5e29be58
src: agent
touches:
  - CHANGELOG.md
  - app/e2e/groovy.mjs
  - app/src/components/FlowStepsView.svelte
  - app/src/lib/store.svelte.ts
  - docs/manual-test.md
  - docs/user-guide.md
  - app/e2e/smoke.mjs
  - app/src-tauri/src/mcp/flow.rs
  - app/src/components/Sidebar.svelte
---

## Decided
* 2026-11-17, the owner: input bodies, headers, mapping and script editors, the XPath expression and the flow steps start empty. Someone without a project loads something under Examples… (the empty editors say so). This replaces the earlier choice that a closed iFlow step goes back to the tab's sample settings.
* "Empty" means the fields a user fills in: Content Modifier rows, Router routes, Splitter and Filter expressions, the converter's source path. Settings that are a choice in SAP's step keep SAP's default (encoder/decoder scheme, canonicalization, digest algorithm, target header `SAPMessageDigest`, converter options).
* The × after "From <iFlow> · <step>" gives that one step its starting settings back, drops its result, invalidates a run under way; other steps keep their iFlow settings, Gather keeps its messages. Closing or switching the project closes all such steps. Nothing is asked: the settings were never saveable and the iFlow is untouched.
* The "+ Script" / "+ Mapping" templates for new files stay: they are skeletons of a new file, not starting values.

## Watch out
* The starting settings live in one function that builds both the initial state and the reset. The MCP server repeats the converter defaults in its flow module; its default source path is now empty too. Change both together.
* The e2e tests can no longer rely on a sample: they type the input, the script and the flow settings, or load an example.

In the code: flowStepDefaults, converterDefaults, closeFlowStep, closeIflowFlowSteps in app/src/lib/store.svelte.ts; converter_defaults in app/src-tauri/src/mcp/flow.rs; empty-hint in XsltView/GroovyView; e2e steps 1 (smoke.mjs, groovy.mjs) and 6b (groovy.mjs).
