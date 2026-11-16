---
title: "The import from CPI Helper is one way and starts nothing: the app only reads the link, and neither script nor mapping runs by itself"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 2, tokens: 585628 }
id: l-d3f4aa05
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
  - app/e2e/smoke.mjs
  - docs/user-guide.md
---

## Decided
- The user asked whether taking a message from CPI Helper means the app calls another tool, and said they would not want the app to call out. It does not: the import only reads a link or pasted text; it sends nothing and calls neither the tenant nor CPI Helper, and CPI Helper is optional (Import… takes JSON too). The docs say so in those words.
- An imported XSLT mapping no longer runs on arrival. A mapping opened from the project runs unless it calls Java, but a link can come from any web page, so for links nothing starts by itself, script or mapping.

## Watch out
- Keep it that way: no feature of this hand-over may open a URL, fetch, or answer back to the sender.
- GroovyDebugX's default target (groovyide.com) and its Contiva option carry the message in the path or query, so those sites receive it; xsltDebugX's default and a Custom URL with `#share/` keep it after `#`.

In the code: importMessage / importXslt in app/src/lib/store.svelte.ts; e2e step 12 in app/e2e/smoke.mjs; docs/user-guide.md "A message from a tenant's trace".
