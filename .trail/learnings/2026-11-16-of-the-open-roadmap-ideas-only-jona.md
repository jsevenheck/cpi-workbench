---
title: "Of the open roadmap ideas only an iFlow overview is of interest; step chains, flow-step history and an in-app debugger are not wanted"
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 1, tokens: 1053 }
id: l-21cab749
scope: repo
status: active
src: agent
touches:
  - docs/plan.md
---

## Decided
- Of the ideas left after the workflow roadmap W1–W10, the owner only finds an iFlow overview interesting (a diagram or ordered view of an imported iFlow, to find a step), and wants it researched before deciding to build it.

## Tried and ruled out
- Run history for the Flow Steps tab: "not interesting for us".
- A chain of steps in the app (several steps run in a row): not wanted; it also risks implying iFlow behaviour without evidence.
- Breakpoints in the app's editor (W10 stage 2): not wanted; the documented JDWP route with jdb or an IDE is the answer.

## Watch out
- Do not start the ruled-out items without asking the owner again.

In the code: docs/plan.md §10.5 ("To decide before any work", W8, W10).
