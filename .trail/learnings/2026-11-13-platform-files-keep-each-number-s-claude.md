---
title: "Platform files keep each number's text when a table saves them, and a project's platform data loads last when it opens"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 3, tokens: 25057 }
id: l-a6e5a305
scope: repo
status: active
src: agent
touches:
  - app/src/lib/platform.ts
  - app/src/lib/store.svelte.ts
---

## Decided
- Adds to "Platform tables in the Groovy tab edit the project's JSON files": a plain JSON.parse / JSON.stringify round trip rewrites numbers (1.50 becomes 1.5, integers beyond 2^53 lose digits), which Jona's learning on Format had already ruled out. A platform document therefore holds every number as a marked string with its text; the saved file gets the text back, a run gets the number. So "what the table does not show stays as it is" holds for numbers too, and a number range's maximum can have any length.
- Opening a project sets the status line before the platform files are read. Reading them first made the open slower, and a test case clicked in that time had its "it has not run" hint overwritten by "Project opened".

## Watch out
- trail check's flag "brings back json stringify parse" on the store was a real finding here, not a word match.
- The one JSON.parse on the text as written is there for its error message and position only.

In the code: parsePlatformFile, platformRunData and platformFileText in app/src/lib/platform.ts; open() in app/src/lib/store.svelte.ts.
