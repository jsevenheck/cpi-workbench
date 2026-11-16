---
title: "Exports from the tenant are told apart by their entries, and value mappings become rows in both directions"
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 39, tokens: 337163 }
id: l-e22e77d8
scope: repo
status: active
src: agent
touches:
  - AGENTS.md
  - CHANGELOG.md
  - README.md
  - app/e2e/groovy.mjs
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/flow.rs
  - app/src-tauri/src/mcp/runs.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/src/components/FlowStepsView.svelte
---

## Decided
- One import entry point (`iflow.import`, the *Import iFlow* button, MCP `import_iflow`) for iFlow, Value Mapping and package exports; the engine picks by entries: `resources.cnt` = package, `value_mapping.xml` = Value Mapping, else iFlow. The button keeps its name because the e2e tests and the docs refer to it.
- A Value Mapping group becomes one row per direction; rows towards an entry with `isDefault="true"` go first, because the table answers with the first match. SAP's docs (S52) say defaults exist for repeated targets; what the tenant returns without a default is an assumption (first group in the file).
- A package's script steps reference script collections by `scriptBundleId`; the import resolves those into the collection's sibling folder `iflows/<uniqueId>/`. Only seen in a real export, nowhere documented.

## Tried and ruled out
- Calling `project.open` with `{path: …}`: the parameter is `dir`, and an empty `dir` opens the engine's working directory, so a trial import wrote into the repository root. Run trial engines with the scratch folder as cwd.

## Watch out
- The user's real exports hold customer data: never commit them or anything derived from them; tests build synthetic zips (`ArtifactImportTest`).
- The MCP shell tests prefer the native XML images in `engine/native/out` over fresh jars; after changing `engine/core`, rebuild them or the tests run old code.

In the code: engine/core/src/main/java/io/cpiwb/core/ArtifactImport.java, ValueMappingImport.java, IflowImport.java (`scriptBundleId`), app/src-tauri/src/engine.rs `locate`.
