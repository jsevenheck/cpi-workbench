---
title: "Hermes's 0.6.0 review: twelve findings checked; the import now leaves out missing choices, and a where-used e2e must outwait the search debounce"
author: Claude
date: 2026-11-17
branch: ccr-20db7c8c-s5pddx
cost: { minutes: 14, tokens: 177821 }
id: l-d05a25de
scope: repo
status: active
src: agent
touches:
  - engine/core/src/main/java/io/cpiwb/core/FlowSteps.java
  - engine/core/src/test/java/io/cpiwb/core/IflowImportTest.java
  - app/e2e/groovy.mjs
  - app/e2e/smoke.mjs
  - app/tests/fidelity.test.mts
  - docs/README.md
  - engine/native/workload.py
  - app/src/lib/flowcalls.ts
---

## Decided
- Review of eebf17c (R01–R12) checked against the code; all twelve held, with two downgraded: R08 (search by raw value) is a contract to state, not a bug, so the UI hint and user guide name it; R12's docs/README.md entry for test-cases.md stays, since that index lists every page, but building.md now links the page.
- The iFlow import (FlowSteps.of) leaves a choice out when the export lacks its key (Encoder/Decoder scheme, Digest canonicalization/algorithm/targetHeader, Gather messageType/algorithm, Filter valueType, Splitter expressionType). Before, it wrote "" or its own default, so the tab's and MCP's `?? default` and the "assumed" note never applied. An explicit empty value is kept for the engine to report.
- Converter namespace lists: both builders drop a pair without prefix or namespace, as the engine does for the list form; the text form would have taken `p = ` as an empty URI.
- iflow.index catches a broken parameters.prop/.propdef per iFlow and returns it as that iFlow's error.
- Route sequence flows now carry their properties in the model; where-used indexes them next to the condition.

## Tried and ruled out
- A where-used e2e that fills the query and then changes the iFlow filter at once: the 250 ms debounce of the typed search runs after the filter change and hides the bug. The test waits 600 ms first; checked to fail without the fix.

## Watch out
- Locally `cargo test` needs the GTK/WebKit dev packages and TAURI_CONFIG='{"bundle":{"externalBin":null,"resources":null}}'.
- The engine workload (engine/native/workload.py) now writes its own smoke case and checks passed == 1: a fresh project has no tests/.

In the code: opt() in engine/core/.../FlowSteps.java; index() in IflowIndex.java; converterText in app/src/lib/flowcalls.ts and converter() in mcp/flow.rs; resetWhereUsed in store.svelte.ts; Fidelity.svelte keydown/close; e2e steps in smoke.mjs (1-) and groovy.mjs (after 6b diagram regressions).
