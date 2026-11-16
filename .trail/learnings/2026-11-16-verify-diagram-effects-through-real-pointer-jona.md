---
title: Verify diagram effects through real pointer input and a non-first iFlow
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
id: l-69dbb0e5
scope: repo
status: active
src: agent
touches:
  - app/e2e/groovy.mjs
  - app/src/components/DiagramView.svelte
---

DiagramView.svelte keeps the view untracked in its selection-centering effect; panning must move a still-selected node fully off screen without re-centering. Verify with actual pointer drags and inspect viewBox after rendering, not just selection classes. Deselect using both Close details and the SVG background with two real imported iFlows, choosing a model other than the first. The fixture models have the same displayed name, so assert the picker value (folder identity), not the label. groovy.mjs runs these regressions when CPIWB_IFLOW_ZIP is set. A single imported model cannot expose fallback-to-first bugs.
