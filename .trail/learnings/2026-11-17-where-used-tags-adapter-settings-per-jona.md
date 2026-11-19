---
title: "Where-used tags adapter settings per adapter, and only from keys seen in public exports"
author: Jona
date: 2026-11-17
branch: feature/0.6.0
cost: { minutes: 22, tokens: 174217 }
id: l-a3c871ab
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/package-lock.json
  - app/package.json
  - app/src-tauri/Cargo.lock
  - app/src-tauri/Cargo.toml
  - app/src-tauri/tauri.conf.json
  - docs/manual-test.md
  - engine/core/pom.xml
  - engine/groovy-v1/pom.xml
  - engine/groovy-v2/pom.xml
---

## Decided
- What a setting holds (address, credential, queue, key alias) is looked up per adapter, not per key name: the same key means different things in different adapters. `alias` is a credential in OData and SuccessFactors but a data source in JDBC.
- Only keys seen in public exports (443 iFlows: SAP's integration recipes plus community repos) get a kind. Any other key is still found by its value, just without a kind. Add a key only with evidence and record it in the plan's finding.
- The MCP tool lists parameters with `parameters: true` instead of adding them to project_info, which stays short.
- A search hit on an adapter selects the sender or receiver system in the diagram, so the system's details now show its adapters' settings. Before that, adapter settings appeared nowhere in the diagram.

## Watch out
- After rebuilding the engine jars, restart the engines on ports 7777–7780 before running the browser e2e. Engines that are still running serve the old classes, and the new RPCs are missing.
- Parameters come back sorted by name, not in parameters.prop order.

In the code: engine/core IflowIndex.ADAPTER_KEYS and kindOf; plan F69 / S55; fixture engine/core/src/test/resources/iflow-index; DiagramView `adapters` derived.
