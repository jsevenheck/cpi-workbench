---
title: Opening a file runs no user code; Linux installers dropped; GitHub outages are not CI failures
author: Claude
date: 2026-11-13
branch: ccr-10144953-rr6o4a
cost: { minutes: 1, tokens: 1514 }
id: l-7739e5a7
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
  - engine/core/src/main/java/io/cpiwb/core/RpcServer.java
  - engine/core/src/main/java/io/cpiwb/core/TextFiles.java
  - ".github/workflows/build.yml"
---

## Decided
* Opening a Groovy script, a Groovy test case or a stylesheet with java: namespaces does not run it. It runs first on Run / Ctrl+Enter; live runs follow after that. Reason: such code runs with the user's rights and may come from an imported iFlow. Other stylesheets still run on open.
* Browser mode refuses requests whose Host header is not localhost / 127.0.0.1 / [::1] or whose Origin is foreign: after DNS rebinding the page is same-origin, so the X-Cpiwb preflight alone did not protect the engine.
* A body or file with an XML declaration naming an encoding is encoded / read in that encoding; a character the encoding lacks is an error, never a silent '?'.
* No Linux installers after 0.3.0 (user's decision, 2026-11-11). Engine tests still run on Linux; the engine jars come from that job.

## Tried and ruled out
* apt retries plus step timeouts for the Linux desktop and e2e jobs: superseded when Linux builds were dropped.

## Watch out
* A test folder name with a quote is invalid on Windows: such tests must skip when Path.resolve throws.
* On 2026-11-11 GitHub's artifact service returned HTML ("Unexpected token '<'") on every upload, and the Ubuntu mirrors were unreachable from runners. Both were outages, not code failures: check which step failed before fixing anything, and re-run once.

In the code:
* store.svelte.ts: armed / autoRunXslt / autoRunGroovy / disarmGroovy; trust.ts: callsJava, isLocalEngine.
* RpcServer.isLocalHost; serve --http supervises a --worker child that restarts after a timeout and exits with its parent.
* TextFiles.declaredCharset / requireEncodable; SimExchange.fromJson.
