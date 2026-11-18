---
title: Every engine call carries its own project folder; overwrite imports report stale files instead of deleting them
author: Jona
date: 2026-11-18
branch: feature/0.6.0
cost: { minutes: 43, tokens: 258420 }
id: l-8191e34d
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/e2e/groovy.mjs
  - app/e2e/smoke.mjs
  - app/scripts/mcp-smoke.mjs
  - app/src-tauri/src/engine.rs
  - app/src-tauri/src/lib.rs
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/flow.rs
  - app/src-tauri/src/mcp/mod.rs
  - app/src-tauri/src/mcp/runs.rs
---

## Decided
* Browser tabs and restarted desktop workers get the project from each call (`projectRoot`), not from engine state or a session id. A missing or unavailable root fails only calls that need project files; it never falls back to the engine's open project. MCP stays stateful with `on_start`.
* XSLT Live runs a separate preview configuration that never binds Java functions; the module scan only produces the "Run required" message. Consent is per Run, there is no approval state to bind to dependencies.
* An overwrite import deletes nothing: files of the earlier version that the export lacks are listed as `kept` (UI status, MCP result). A failed create-new import removes only entries it created.
* Desktop queue wait = startup + exchange + 3 s (50 s), so a file save waits out the longest call ahead instead of failing "Engine busy" after 5 s.

## Tried and ruled out
* A 5 s desktop slot wait: file reads/saves failed during ordinary 10–30 s runs on the same engine.
* Freeing an MCP request id after flushing its answer: a client reusing the id at once got "already in use". The id is freed (and the cancel flag read) under the in-flight lock before sending.

## Watch out
* Because the writer frees the id before a blocked write, one extra request can be admitted while stdout stalls; tests must check the admission bound, not which request id got the free slot.
* `build.yml` does not run on pushes to feature branches: open a PR to main or use *Run workflow*.
* `trail check` on this diff gave 20 keyword-only matches (project.open/path, groovy 1→2); none reintroduced a ruled-out approach.

In the code: ProjectFiles.scoped, RpcServer /project/rpc, engine.rs Deadlines and call_desktop, mcp/mod.rs serve writer, IflowImport.publish/kept, XsltStep preview (ALLOW_EXTERNAL_FUNCTIONS=false), app/src/lib/engine.ts rawCall.
