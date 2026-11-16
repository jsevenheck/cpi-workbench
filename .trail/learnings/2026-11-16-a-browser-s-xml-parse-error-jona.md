---
title: "A browser's XML parse error is told by its form, because a document may have its own parsererror element"
author: Jona
date: 2026-11-16
branch: claude/nice-turing-nql3ja
cost: { minutes: 43, tokens: 234030 }
id: l-55ea5e23
scope: repo
status: active
src: agent
touches:
  - app/src/lib/share.ts
  - app/src-tauri/src/receiver.rs
  - app/src/lib/receiver.svelte.ts
  - app/src/lib/store.svelte.ts
  - app/src-tauri/src/lib.rs
  - app/src/lib/xmltext.ts
---

## Decided
- DOMParser reports a parse error by adding a `parsererror` element; there is no error flag. Chromium/WebKit put an XHTML `parsererror` (with a `style` attribute and an `h3` first) into the document; Firefox replaces the whole document with a `parsererror` root in its own namespace, text first ("XML Parsing Error: … Line Number N, Column M:").
- So an error is recognised by that form, per engine, and the message is normalised to "error on line N at column M: …".

## Tried and ruled out
- Looking for any `parsererror` element: valid business XML with such an element got the "not well-formed" warning.
- Matching the namespace of the browser's error only: Chromium uses the public XHTML namespace, so `<h:parsererror>` in valid XML still matched.

## Watch out
- Node has no DOMParser, so the unit tests cannot cover the XML branch; check it in Edge and Firefox with Playwright (bundle share.ts with esbuild, call bodyWarning in the page).
- Firefox names "no root element found" even for a cut-off document; that is its own text.

In the code: parseError and bodyWarning in app/src/lib/share.ts.
