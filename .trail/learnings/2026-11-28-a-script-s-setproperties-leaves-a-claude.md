---
title: "A script's setProperties leaves a null value out as setProperty does, an assumption until a tenant shows otherwise"
author: Claude
date: 2026-11-28
branch: ccr-28ad8cbd-y1wrjm
cost: { minutes: 1, tokens: 1486 }
id: l-abb70199
scope: repo
status: active
src: agent
---

## Decided
- `message.setProperties(map)` with a null value threw a NullPointerException (Camel's property map is a ConcurrentHashMap, `putAll` rejects null). It sets each entry through Camel's `setProperty`, which removes a property set to null, as a single `setProperty(name, null)` already did. fidelity.md labels it as an assumption in its own row, so the flow steps' basis is not lowered.
- The null-property warning from a successful Camel Simple evaluation (Router, Filter, Splitter, Converter) goes into the step's diagnostics once, in flowcalls.ts and mcp/flow.rs alike.

## Watch out
- SAP's implementation might throw instead. A tenant probe with `setProperties([a: '1', b: null])` settles it; then adjust the behaviour and the fidelity row.
- The Saxon `QName.getNamespaceURI` deprecation warnings cannot be fixed in the shared XML sources: Saxon 9.9 has no `getNamespaceUri()`; a fix needs the per-version glue in `engine/xml/src/saxon12`.

In the code: MessageSupport.setProperties; runStep in flowcalls.ts and run_step in mcp/flow.rs; testdata/flow-parity/*-null-property.json.
