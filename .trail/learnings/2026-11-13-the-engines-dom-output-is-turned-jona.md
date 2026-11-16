---
title: "The engines' DOM output is turned into LF after writing, because the JDK serializer ignores line-separator settings"
author: Jona
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 6, tokens: 85130 }
id: l-59ac6848
scope: repo
status: active
src: agent
touches:
  - engine/core/src/main/java/io/cpiwb/core/DomXml.java
---

## Decided
Every DOM-to-text serialization in the engines goes through one helper that replaces the computer's line separator with LF after the JDK identity transformer writes. On Windows the transformer writes CRLF for every `\n` in text nodes and after a declaration, so split parts and Gather results differed by OS.

## Tried and ruled out
- Output properties `{http://xml.apache.org/xalan}line-separator` and `{http://xml.apache.org/xslt}line-separator`: the JDK's built-in XSLTC transformer ignores both; CR is still written.

## Watch out
- The replacement is safe only because the serializer writes a real CR in text or attributes as `&#13;`; a raw CRLF in output can only come from the serializer itself.
- New DOM serialization code must use the helper, or Windows output gets CRLF again. The tests that catch it fail only on Windows (CI runs Windows too).

In the code: engine/core/src/main/java/io/cpiwb/core/DomXml.java; used in SplitStep, GatherStep (Combine at XPath), XPathStep.describeDom, ScriptRunner.xml (SOAP headers). Tests: SplitStepTest.partsUseLfOnEveryComputer, GatherStepTest.combineAtXPathUsesLfOnEveryComputer.
