---
title: "The Groovy tab reads a script's functions from its text, because compiling it would already run code"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 24, tokens: 145055 }
id: l-78438400
scope: repo
status: active
src: agent
touches:
  - CHANGELOG.md
  - app/e2e/smoke.mjs
  - app/src/App.svelte
  - app/src/components/FileView.svelte
  - app/src/lib/store.svelte.ts
  - docs/user-guide.md
  - app/e2e/groovy.mjs
  - app/src/app.css
  - app/src/components/FlowStepsView.svelte
  - app/src/components/GroovyView.svelte
---

## Decided
- The Function picker (script steps and Message Mapping functions, with parameter names) is filled from a lexical reading of the script text, not from the engine. Opening a script must not run its code, and compiling Groovy can run code (AST transformations), so asking the engine before the first run is not an option. After a run, the engine's list and the engine's parameters win; when the engine called a function as a script step although the text reads like a mapping function, that wins too (groovy.stepFunction).
- The text rules mirror the engine's MappingFunction: String/Object/untyped = single value, String[]/Object[] = context, Output, MappingContext; one Object/untyped/Message parameter = script step; any other type = not a mapping function; static, private, class members and `run` are skipped.
- A script with only mapping functions opens with its first one in source order (the engine's fallback picks alphabetically; it still applies when the text finds nothing).
- Picking a function resets the arguments and only auto-runs code the user has already started (autoRunGroovy), since the picker is visible before the first run.
- The String[] argument editor keeps the JSON text (one context per line) as the source of truth, so case.json, agents and the Text view stay one format.

## Watch out
- The reader is a heuristic (regex over code with comments and strings blanked): parameter lists with nested parentheses (default values with calls) or declarations split before the name are missed. The engine list after a run covers those.
- A bindable Svelte prop with a default must not get undefined: groovy.args is [] after a function change, so QueueEditor's value has no default.

In the code: app/src/lib/functions.ts (scriptFunctions, tests in app/tests/functions.test.mts); selectedMapping() and the open-time pick in app/src/lib/store.svelte.ts; picker and argument form in GroovyView.svelte; QueueEditor.svelte. e2e helpers fnName/setOtherFn in app/e2e/groovy.mjs.
