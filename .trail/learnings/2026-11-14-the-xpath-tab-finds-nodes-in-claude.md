---
title: "The XPath tab finds nodes in the text by element positions, not line numbers, and counts each parent's children once"
author: Claude
date: 2026-11-14
branch: claude/nice-turing-nql3ja
cost: { minutes: 15, tokens: 654415 }
id: l-28834963
scope: repo
status: active
src: agent
touches:
  - app/src/lib/xmltext.ts
  - app/tests/xmltext.test.mts
  - engine/xml/src/main/java/io/cpiwb/xml/XPathStep.java
---

## Decided
- The engine gives each node of the body its place as element positions from the root (`at`: path, the element's name as written, an attribute's name, or text). The JDK's DOM (XPath 1.0) has no line numbers, and Saxon's are the end of the start tag, so positions work alike on both versions. The UI finds the place with a small scanner and marks it only where the element name matches, so a DTD entity that holds elements cannot put a mark in the wrong place.
- The same scanner writes "XPath at cursor": this node (positions only where siblings share the name) or all like it, with the Namespace mapping's prefixes; an unmapped namespace becomes `Q{uri}name` (3.1) or `local-name()` (1.0).
- Marks only while the text is the one the run was for; at most 1000; the document is scanned only when a result has places.

## Tried and ruled out
- Counting preceding siblings per node: quadratic. 20,000 matches took 14 s in the engine; counting each parent's children once per run (a position cache) brought it to 0.25 s.

## Watch out
- Monaco draws decorations on the next animation frame: an e2e check that marks are gone must wait for them to detach, not count at once.

In the code: domPlace / saxonPlace with domPosition / saxonPosition in engine/xml/src/main/java/io/cpiwb/xml/XPathStep.java (test xpathNodesSayWhereTheyAre); app/src/lib/xmltext.ts (tests in app/tests/xmltext.test.mts); XPathView.svelte; highlights, cursorOffset, selectRange in Editor.svelte.
