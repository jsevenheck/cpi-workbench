---
title: Browser-mode requests wait out the call ahead like desktop ones; the JDK request timer does not cut long runs
author: Claude
date: 2026-11-19
branch: feature/0.6.0
cost: { minutes: 9, tokens: 371133 }
id: l-a00344d3
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - docs/architecture.md
  - docs/user-guide.md
  - engine/core/src/main/java/io/cpiwb/core/RpcServer.java
  - engine/core/src/test/java/io/cpiwb/core/IflowImportTest.java
  - engine/core/src/test/java/io/cpiwb/core/RpcHttpTest.java
  - app/e2e/groovy.mjs
  - app/e2e/smoke.mjs
  - app/scripts/mcp-smoke.mjs
  - app/src-tauri/src/engine.rs
---

## Decided
- A queued browser request waits the engine's execution timeout plus 5 s (35 s by default), the same rule as the desktop queue: one engine serves files and runs in browser mode, so a 5 s wait made saves fail during ordinary 10–30 s runs.

## Tried and ruled out
- Suspecting `sun.net.httpserver.maxReqTime=10` would abort browser calls over 10 s: a 15 s Groovy run over /project/rpc returned normally; the timer only covers reading the request.

## Watch out
- Cloud sessions run tests as root: permission-based tests (r-x folders) do not block deletes there.
- Release notes come only from the `## [<version>]` section of the changelog; fixes listed under Unreleased before tagging v0.6.0 are left out of its notes.

In the code: HTTP_QUEUE_MARGIN_MS and queueMs in engine/core/.../RpcServer.java, test aWaitingRequestOutlivesTheCallAhead in RpcHttpTest; desktop counterpart DEADLINES in app/src-tauri/src/engine.rs.
