---
title: "Gather's Combine only warns about a part that is not XML until a tenant shows what CPI does"
author: Jona
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 4, tokens: 43928 }
id: l-692acb14
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - docs/fidelity.md
  - docs/plan.md
  - docs/status.md
  - engine/xml/src/main/java/io/cpiwb/xml/GatherStep.java
  - engine/xml/src/test/java/io/cpiwb/xml/GatherStepTest.java
  - probes/README.md
  - probes/baseline-simulator/gather/json-differentXml.xml
  - probes/baseline-simulator/gather/json-sameXml.xml
  - probes/baseline-simulator/gather/mixed-differentXml.xml
---

## Decided
Combine with a JSON or plain-text part keeps its old output (the text inside the multimap wrapper, ok true) and adds a warning. The user agreed to change behaviour only after a tenant answers, because SAP documents Combine for XML only and no SAP source shows this case. Combine at XPath already fails for such a part, because it has to parse every message.

## Tried and ruled out
- Failing the step like Combine at XPath: rejected because it would claim CPI behaviour without evidence.
- Searching SAP docs and KBAs: nothing covers a non-XML part in Combine.

## Watch out
- When the tenant results come back, adjust the behaviour, the warning text, fidelity.md and close Q-F1 in plan.md together.
- The probe's baseline was made with the MCP tools (Splitter, then groovy-1.x per part by runId with CamelSplitIndex set by hand, then Gather with messagesFrom), because flow steps have no CLI command.

In the code: engine/xml/src/main/java/io/cpiwb/xml/GatherStep.java (combine, notWellFormed); probes/gather/, probes/baseline-simulator/gather/, probes/README.md step 8; docs/plan.md Q-F1.
