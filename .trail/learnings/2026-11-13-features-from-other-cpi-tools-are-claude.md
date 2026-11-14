---
title: "Features from other CPI tools are taken over only when they work offline; the list and the order are in the plan's roadmap"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 16, tokens: 756505 }
id: l-5f73b7c9
scope: repo
status: active
src: agent
touches:
  - app/src-tauri/src/mcp/tools.rs
  - engine/xml/src/main/java/io/cpiwb/xml/GatherStep.java
  - CHANGELOG.md
  - docs/fidelity.md
  - docs/plan.md
  - docs/status.md
  - engine/xml/src/test/java/io/cpiwb/xml/GatherStepTest.java
  - probes/README.md
  - probes/baseline-simulator/gather/json-differentXml.xml
  - probes/baseline-simulator/gather/json-sameXml.xml
---

## Decided
- After a review of other CPI tools (2026-11-13) the user asked for the take-over list in the roadmap. Planned as Phase 6, in order W1 to W10: platform data, attachments and SOAP headers in the Groovy tab; a traced message from CPI Helper; XPath comfort; Encoder / Decoder / Message Digest steps; JUnit output; compare rules for test cases; Value Mapping import; run history; examples; debugger attach.
- Not taken over, with reasons in the same section: anything that connects to a tenant, server-side runs, AI functions inside the app, a governance lint for iFlows, a mock tenant, utility tools that are no CPI steps, share links, a VS Code extension, and saving a test case from the app.
- Only ideas are taken: XSLTDebugX is AGPL-3.0 and CPI Helper GPL-3.0, so none of their code may be copied into this Apache-2.0 repository.

## Watch out
- The review read pages and READMEs only; nothing was installed. SAP Help's simulation pages, the JDI debugging blog and IntegratedGroovy could not be opened, so claims about them come from search results.
- A step chain in the app and an iFlow diagram are listed as "to decide first", not as planned.

In the code: docs/plan.md §10.5 (tools table, W1–W10, not taken over), roadmap row "6 — Workflow", questions Q-T2, Q-E1, Q-V1, Q-D1, sources S33–S46; docs/status.md "Next steps".
