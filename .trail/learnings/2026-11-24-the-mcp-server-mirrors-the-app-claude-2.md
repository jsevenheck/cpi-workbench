---
title: "The MCP server mirrors the app: a workspace of projects, and Where used and the script function reader through parity fixtures"
author: Claude
date: 2026-11-24
branch: claude/optimistic-dijkstra-ek2iek
cost: { minutes: 7, tokens: 65336 }
id: l-8f03408f
scope: repo
status: active
replaces: l-be545910
src: agent
touches:
  - AGENTS.md
  - CHANGELOG.md
  - app/src-tauri/Cargo.lock
  - app/src-tauri/Cargo.toml
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/functions.rs
  - app/src-tauri/src/mcp/mod.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/tests/functionparity.test.mts
---

## Decided
* User (2026-11-24): what the app can do, the MCP server should do too, with the same engine calls. The app leads. This reverses the 2026-11-13 choice not to port the Groovy tab's function reader.
* `--workspace <folder>` serves its direct subfolders; `open_project` opens one or creates one (create + init, like Import iFlow without a project). Engine calls carry the open project (`project.request` with `projectRoot`), so switching needs no engine restart; open_project takes an exclusive lock, other tool calls a shared one.
* Logic the UI computes in TypeScript is repeated in Rust and checked by a fixture both sides replay: Where used (`whereused.rs`, testdata/whereused-parity) and the function reader (`functions.rs`, testdata/functions-parity, expected values generated from functions.ts). The function reader uses the `regex` crate (already in the build via tauri-utils) with the TypeScript patterns, ASCII classes spelled out because JavaScript's `\w` and `\b` are ASCII.
* Where used's overview sorts ignoring case, then by code point, on both sides: `localeCompare` follows the system language and cannot be repeated in Rust.

## Tried and ruled out
* open_project with any path: the project boundary would be gone. MCP roots: not checked in Claude Desktop, and no less configuration.
* Listing functions by compiling the script for read_file: compiling Groovy can run code (AST transformations); reading must not.

## Watch out
* A headless agent run (claude -p, --strict-mcp-config, only mcp__cpi-workbench) costs about 0.3 USD and 40 s and finds the friction; reports mix real issues with fixture quirks (the iflow test fixture lacks gone.gsh and the `enrich` function on purpose) and unverified CPI claims.
* The engine's search matches the raw property text: in a table a quote is `&apos;`, so a query with `'` does not hit it.
* Piping requests and closing stdin at once drops running tool calls; keep stdin open when trying the binary by hand.

In the code: app/src-tauri/src/mcp/{mod,files,tools,whereused,functions}.rs; app/tests/{whereparity,functionparity}.test.mts; ScriptRunner.stepFunctions.
