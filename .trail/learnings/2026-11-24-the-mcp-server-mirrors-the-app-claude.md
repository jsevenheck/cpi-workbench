---
title: "The MCP server mirrors the app: a workspace of projects, and Where used through a shared parity fixture"
author: Claude
date: 2026-11-24
branch: claude/optimistic-dijkstra-ek2iek
cost: { minutes: 15, tokens: 117500 }
id: l-be545910
scope: repo
status: active
replaces: l-9b82c894
src: agent
touches:
  - AGENTS.md
  - CHANGELOG.md
  - app/src-tauri/src/mcp/mod.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/src-tauri/src/mcp/whereused.rs
  - app/src/lib/whereused.ts
  - app/tests/whereparity.test.mts
  - docs/mcp.md
  - testdata/whereused-parity/cases.json
---

## Decided
* User (2026-11-24): what the app can do, the MCP server should do too, with the same engine calls. The app leads.
* `--workspace <folder>` serves its direct subfolders; `open_project` opens one or creates one (create + init, like Import iFlow without a project). Engine calls carry the open project (`project.request` with `projectRoot`), so switching needs no engine restart; the `on_start` hook is gone. open_project takes an exclusive lock, other tool calls a shared one.
* Where used: `mcp/whereused.rs` repeats `whereused.ts` (overview, parameter matches, resolved {{parameters}}, table rows); both replay `testdata/whereused-parity`, like flow-parity. The overview now sorts ignoring case, then by code point, on both sides: `localeCompare` follows the system language and cannot be repeated in Rust.

## Tried and ruled out
* open_project with any path: the project boundary would be gone. MCP roots: not checked in Claude Desktop, and no less configuration.

## Watch out
* A headless agent run (claude -p, --strict-mcp-config, only mcp__cpi-workbench) costs about 0.3 USD and 40 s and finds the friction; reports mix real issues with fixture quirks (the iflow test fixture lacks gone.gsh and the `enrich` function on purpose) and unverified CPI claims.
* The engine's search matches the raw property text: in a table a quote is `&apos;`, so a query with `'` does not hit it.
* Piping requests and closing stdin at once drops running tool calls; keep stdin open when trying the binary by hand.

In the code: app/src-tauri/src/mcp/{mod,files,tools,whereused}.rs; tests open_project_stays_inside_the_workspace, a_workspace_switches_and_creates_projects, whereused_parity; app/tests/whereparity.test.mts.
