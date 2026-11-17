---
title: Adding an MCP tool means updating the tool count and list in several places
author: Jona
date: 2026-11-17
branch: feature/0.6.0
cost: { minutes: 28, tokens: 299355 }
id: l-7324a99c
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - CHANGELOG.md
  - app/src-tauri/src/mcp/tests.rs
  - app/src/App.svelte
  - app/src/components/Fidelity.svelte
  - app/src/components/FlowStepsView.svelte
  - app/src/lib/fidelity.ts
  - app/tests/fidelity.test.mts
  - app/vite.config.ts
  - docs/fidelity.md
---

## Watch out
- The number of MCP tools is written down in four places besides the tool list itself; a new tool fails the handshake test until all agree.
- The unit tests run on plain Node without a bundler, so a module under app/src/lib that a test imports must not import values from sibling modules (type-only imports are fine). Logic the tests need goes into such a module, e.g. the flow-step calls and Camel Simple helper live together for that reason.

In the code: app/src-tauri/src/mcp/tests.rs (tool_definitions_follow_the_spec names and read-only list; legacy_handshake_negotiates_the_version counts tools), app/scripts/mcp-smoke.mjs (tools/list length), docs/manual-test.md row 5.3, docs/status.md MCP row, docs/mcp.md tools table. Node tests: app/tests/*.test.mts import ../src/lib/<name>.ts.
