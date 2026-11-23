---
title: "An iFlow step's missing settings run with defaults, but are named as assumptions"
author: Jona
date: 2026-11-17
branch: feature/0.6.0
cost: { minutes: 14, tokens: 65743 }
id: l-a7c5ee76
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/src-tauri/src/mcp/flow.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src/components/FlowStepsView.svelte
  - app/src/lib/flowcalls.ts
  - app/src/lib/store.svelte.ts
  - app/tests/flowparity.test.mts
  - docs/fidelity.md
  - docs/mcp.md
  - docs/user-guide.md
---

## Decided
- When an exported iFlow step leaves out a choice (Encoder/Decoder scheme, Message Digest canonicalization/algorithm/target header, Splitter type, Gather type/algorithm, Filter value type), it runs with the Flow Steps tab's default. The app and MCP must say so. The owner chose this on 2026-11-17 over going back to "(none given)" with a failing run.
- The reason: whether CPI uses the same default for a setting missing from an export is not known. AGENTS.md forbids failing silently and requires labelling assumptions.

## Tried and ruled out
- Plain defaults with no note (as in P2): a code review flagged this as a silent fallback that could give a plausible result for a configuration the iFlow never had.
- Adding a row to the per-step fidelity tables: that would mark every one of these steps as "Assumption" in the indicator, even when the iFlow sets everything. The row went into the Local workflow table instead.

## Watch out
- The list of choices exists twice, in TypeScript and in Rust. A Rust test reads the TypeScript list line by line, so keep one entry per line.

In the code: flowcalls.ts ASSUMED_DEFAULTS / defaultedSettings; mcp/flow.rs ASSUMED_DEFAULTS / defaulted, result step.defaulted; flow.defaulted in store.svelte.ts; test assumed_defaults_match_the_tab.
