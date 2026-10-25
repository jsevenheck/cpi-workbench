---
title: Editor Format re-indents only whitespace that cannot reach the result; verify changes by re-indenting real files and rerunning the engine tests
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 10, tokens: 65073 }
id: l-d562d407
scope: repo
status: active
touches:
  - app/src/lib/indent.ts
  - app/tests/format.test.mts
---

## Decided
* Format for mappings and scripts (`app/src/lib/indent.ts`) is a line re-indenter, not a parse and re-serialize. Re-serializing XSLT through the engine's `xml.format` would drop CDATA, expand entities and could change whitespace-sensitive text. Groovy has no offline formatter.
* XSLT: a line moves only if the text before it is whitespace between element tags (XSLT strips that from a stylesheet). Text lines, `xsl:text`, `xml:space="preserve"`, CDATA, comment bodies and non-XPath attribute values stay. Groovy: multi-line strings, slashy strings and `${}` are lexed so their text never moves.

## Watch out
* Unit tests only cover hand-made cases. After changing `indent.ts`, also re-indent the demo project and `engine/groovy/src/test/resources` with a different unit (tabs), so every movable line moves. Run the demo cases and `mvn -f engine/pom.xml test`, then `git checkout` the resources. On 2026-10-25 that moved 66 + 119 lines and everything still passed.
