---
title: A second tool review chose four items for 0.6.0; several suggestions were already done or not true
author: Jona
date: 2026-11-17
branch: feature/0.6.0
cost: { minutes: 6, tokens: 55139 }
id: l-49e0793b
scope: repo
status: active
src: agent
touches:
  - docs/plan.md
---

## Decided
- 2026-11-17 the owner had an external (ChatGPT) review of other CPI tools double-checked. Taken for 0.6.0: internal test cases, UI-MCP parity tests for Flow Steps, a fidelity indicator, an offline where-used and parameter overview.

## Tried and ruled out
- Atomic package import as a priority: the import already checks every folder collision before writing; only a failure mid-write with overwrite is left.
- JDWP setup for IntelliJ and VS Code as new work: the debugging page already has both; only trying them in a real IDE (Q-D1) is open.
- "SAP Cloud Integration has native GitHub support since 2026": SAP's July 2026 monthly update still says there is no native GitHub integration. Do not cite it as fact.
- Flow-step test cases for users and an MCP run_tests tool: they would make test cases a user feature, which the owner does not want.

## Watch out
- The review used three web searches and READMEs; no tool was installed or tried.

In the code: docs/plan.md §10.6 (P1-P4 and the not-taken list).
