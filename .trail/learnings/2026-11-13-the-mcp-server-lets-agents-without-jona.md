---
title: The MCP server lets agents without file tools work in the project and chain steps by runId
author: Jona
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 1, tokens: 2482 }
id: l-3c21a04f
scope: repo
status: active
replaces: l-601af56e
src: agent
---

## Decided
* Goal (user, 2026-11-13): Claude Desktop develops a step like a person in the app (open files, write, press Run). Test cases are the app's regression cases and stay out of MCP.
* read_file / save_file / import_iflow give file access through the engines' ProjectFiles checks; writes never replace without `overwrite: true`; absolute paths, drive letters and `..` are refused in Rust first.
* `reference` is generated (completions.ts, clean-room classes, docs via include_str!), so it cannot drift.
* Results chain by runId (opaque per-process handles, 2026-07-28 stateful-tool guidance): `messageFrom` / `messagesFrom`, `runId/n` for Splitter parts. Unchanged bodies are left out of results and long ones shortened; read_file runId / save_file contentFrom take the whole body.

## Tried and ruled out
* Intermediate files in inputs/ for chaining: they stay in the project and lose headers and properties.

## Watch out
* A headless agent run limited to the MCP tools is the quickest way to see whether a tool change helps; it reports friction in detail when asked to be critical.
* validate_xsd always uses the JDK's XSD 1.0 validator; `runtime` only picks the engine process.

In the code: app/src-tauri/src/mcp/{files,runs,reference}.rs; tests files_are_read_saved_and_imported_inside_the_project, runs_chain_by_id. Headless check: claude -p … --mcp-config … --strict-mcp-config --allowedTools mcp__cpi-workbench --disallowedTools Bash,Read,Write,Edit,…
