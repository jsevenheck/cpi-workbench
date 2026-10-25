---
title: "XML runs stay inside the project: every parse goes through ProjectResolver.source()"
author: Jona
date: 2026-10-25
branch: main
cost: { minutes: 35, tokens: 237975 }
id: l-ee37c462
scope: repo
status: active
touches:
  - app/e2e/smoke.mjs
  - app/src/components/XPathView.svelte
  - app/src/components/XsdView.svelte
  - docs/status.md
  - ".github/workflows/build.yml"
  - AGENTS.md
  - README.md
  - app/e2e/groovy.mjs
  - app/package.json
  - app/src-tauri/src/engine.rs
---

## Decided
- Every XML document an XSLT/XPath run parses (stylesheet, includes, input body, doc()/document() results, XPath 1.0 DOM input, xml.format) goes through `ProjectResolver.source()` / `xmlReader()` in `engine/xml/src/main/java/io/cpiwb/xml/ProjectResolver.java`. That SAX reader resolves external entities and DTDs via `ProjectResolver.load`, which blocks anything outside the project and the network. Internal entities still work.
- `unparsed-text()` (and json-doc) and `collection()` are configuration-wide hooks in Saxon, so `ProjectResolver.install(Configuration)` routes them to the run's resolver through a ThreadLocal (`begin()` in the run, `end()` in a finally). Both Saxon 9.9 and 12 have the same `UnparsedTextURIResolver`, `CollectionFinder`, `XPathSelector.setURIResolver` and `XsltController.setResultDocumentResolver` signatures, so none of this needs `SaxonCompat`.
- `xsl:result-document` wrote to any path; it is now `UnsupportedInSimulator`, as is `collection()`.
- `engine/xml/src/test/java/io/cpiwb/xml/XmlBoundaryTest.java` and the entity/unparsed-text requests in `engine/native/workload.py` guard this on JVM and native.

## Tried and ruled out
- Setting `javax.xml.accessExternalDTD=""` globally: it blocks project-local entities too and depends on when the JDK reads the property, which is uncertain in a GraalVM native image. Per-reader entity resolution works on both.

## Watch out
- Reflexive `java:` calls run arbitrary JDK code and are NOT covered by the boundary; the docs say so. Groovy has no sandbox by decision (user guide: run trusted scripts only).
- A new place that builds a `StreamSource` from XML bypasses the boundary. Use `resolver.source(...)` instead.
- Native images need the Xerces message bundles (`XMLMessages`, `XMLErrorResources`, ...) listed in `resource-config.json`, or parser errors fail with a missing-bundle error instead of the real message.
