---
title: "Test cases are only for this project's regression tests, not a feature for users"
author: Jona
date: 2026-11-17
branch: main
cost: { minutes: 1, tokens: 1132 }
id: l-c36c2ed2
scope: repo
status: active
src: agent
touches:
  - docs/test-cases.md
  - docs/plan.md
  - docs/user-guide.md
  - app/src-tauri/src/mcp/reference.rs
  - ".github/workflows/build.yml"
---

## Decided
- 2026-11-17, the owner: users test a step by running it in the app and looking at the result. They should not write `case.json` test cases or run tests from the CLI or MCP. Test cases exist for this repo's own regression tests in CI.
- So no flow-step test cases for users and no MCP `run_tests` tool. Flow-step coverage comes from internal UI ⇄ MCP parity tests instead.
- Test cases are not presented to users as a workflow; hiding or removing them from user docs and the app is fine with the owner.

## Watch out
- `docs/test-cases.md` also documents the `platform/` file formats, which users need and which the MCP `reference` tool reads. Those formats must stay in user-facing docs.

In the code: docs/plan.md §10 "Remaining work"; docs/user-guide.md:84; app/src-tauri/src/mcp/reference.rs:14; .github/workflows/build.yml regression step.
