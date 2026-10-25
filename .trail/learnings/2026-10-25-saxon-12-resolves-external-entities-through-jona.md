---
title: "Saxon 12 resolves external entities through Configuration.setResourceResolver, not ParseOptions"
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 22, tokens: 133167 }
id: l-b7b7973c
scope: repo
status: active
touches:
  - ".github/ISSUE_TEMPLATE/bug_report.yml"
  - ".github/ISSUE_TEMPLATE/config.yml"
  - ".github/ISSUE_TEMPLATE/feature_request.yml"
  - ".github/pull_request_template.md"
  - ".github/workflows/build.yml"
  - AGENTS.md
  - CHANGELOG.md
  - CONTRIBUTING.md
  - LICENSE
  - README.md
---

## Decided
- Adds to the learning "XML runs stay inside the project": on Saxon 12, `parse-xml()` in XPath ignores the entity resolver in `ParseOptions` (verified with jshell: it is never called). Saxon 12 asks the configuration's `ResourceResolver` with nature `ResourceRequest.EXTERNAL_ENTITY_NATURE` / `DTD_NATURE` instead. `SaxonCompat.setEntityResolver` (saxon12) therefore sets both and answers only those two natures; other requests return null, so Saxon keeps its own handling.
- Saxon 9.9 uses `config.getParseOptions().setEntityResolver(...)` for `parse-xml()`.
- Both delegate to the run's `ProjectResolver` via the ThreadLocal set by `begin()`.

## Tried and ruled out
- An `XMLReaderMaker` in Saxon 12's ParseOptions: did not stop the leak either.

## Watch out
- When a reviewer finds another way to reach files, first check with jshell against `engine/xml-he12/target/cpiwb-xml-he12.jar` which Saxon hook is actually called; Saxon 9.9 and 12 differ.
- Test: `XmlBoundaryTest.parseXmlCannotReadEntitiesOutside`, plus the parse-xml request in `engine/native/workload.py`.
