---
title: "The iFlow diagram is drawn by hand from the layout the export stores, and the list walks the flows from the start events"
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 33, tokens: 163127 }
id: l-0bc9483b
scope: repo
status: active
src: agent
touches:
  - docs/plan.md
  - engine/core/src/main/java/io/cpiwb/core/IflowModel.java
  - engine/core/src/test/java/io/cpiwb/core/IflowModelTest.java
  - app/src/lib/iflowmodel.ts
  - app/tests/iflowmodel.test.mts
  - app/src/components/DiagramView.svelte
  - app/e2e/groovy.mjs
---

## Decided
- The Diagram tab draws the iFlow from the `bpmndi` shapes and waypoints of the export (absolute coordinates, waypoints from centre to centre, so each line is cut at its shapes). A hand-written SVG: bpmn-js would render it too, but its licence requires a visible bpmn.io mark; Mermaid loses the editor's layout.
- The engine only extracts (processes, all elements, flows, layout); the order of the list and the geometry are computed in the UI, where they are unit-tested without an engine.
- The list walks each process from its start events: a chain stays at one depth, a gateway's routes are indented with name and condition, a step reached twice is listed again as a reference, unreachable elements come last. Steps a tab runs open there; everything else opens in the Diagram tab with its properties, read-only.
- Main vs. local process only from the process's `cmdVariantUri` (`LocalIntegrationProcess`), never from names; the Router's default only from the gateway's `default` attribute.

## Watch out
- The diagram captures the pointer for panning, so click and double-click events land on the svg, not on the shape: find the element with `elementFromPoint(...).closest('[data-node]')`.
- Read an element's own properties from its direct `extensionElements` only; the older `IflowImport.properties` searches all descendants and would mix a process's settings with its steps'.
- The shell tests use the native XML images; after changing `engine/core`, rebuild them.

In the code: engine/core/src/main/java/io/cpiwb/core/IflowModel.java, app/src/lib/iflowmodel.ts, app/src/components/DiagramView.svelte, the fixture engine/core/src/test/resources/iflow (now with processes, flows and layout).
