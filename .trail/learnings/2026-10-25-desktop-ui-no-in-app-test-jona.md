---
title: "Desktop UI: no in-app test runner, JSON inputs belong to Groovy, and svelte-check misses $state placement errors"
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 48, tokens: 331367 }
id: l-3671448d
scope: repo
status: active
touches:
  - app/src/lib/split.svelte.ts
  - app/src/lib/store.svelte.ts
  - app/src/lib/json.ts
  - app/src/App.svelte
---

## Decided
* The Tests tab and "Save as test" were removed from the app (2026-10-25) because the user did not need them. Test cases stay a CLI/CI feature; the app only opens a `case.json`.
* A JSON input (`.json`, or a body starting with `{`/`[`) always loads into the Groovy tab (`setInput` in `store.svelte.ts`). XSLT, XSD and XPath take XML only.
* Files outside the step kinds (platform JSON, `cpi-workbench.json`) open in the File tab. Only files under `inputs/` replace an input body, so opening a file never discards a typed input.

## Tried and ruled out
* `JSON.stringify(JSON.parse(body), null, 2)` for Format: it rewrites numbers (15.50 → 15.5, big integers lose digits). `app/src/lib/json.ts` re-indents tokens instead.

## Watch out
* `return $state({...})` in a `.svelte.ts` file passes `npm run check` but breaks the Vite build with `state_invalid_placement`. Assign it to a const first (`split.svelte.ts#paneSizes`). Check the dev server or `npm run build`, not only svelte-check.
