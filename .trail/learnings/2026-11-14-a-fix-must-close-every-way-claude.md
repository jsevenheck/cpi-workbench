---
title: "A fix must close every way into the bug: the re-review found the same five defects by other entry points"
author: Claude
date: 2026-11-14
branch: claude/nice-turing-nql3ja
cost: { minutes: 12, tokens: 223330 }
id: l-398b3164
scope: repo
status: active
src: agent
touches:
  - app/src/lib/store.svelte.ts
  - app/src/lib/xmltext.ts
  - app/src/lib/platform.ts
---

## Decided
The re-review of 86beb74 (hermes) found residues of F02, F05, F06 and F08 and one new regression (N01). All five held against the code; each now has a test that fails without its fix:
- Changing the Groovy function goes through one store function (setGroovyFunction) from the list and from the typed "Other name…" field. It drops the arguments, the reported mapping parameters and a run still under way (groovySeq), so a late answer of the old function cannot drop the new one's arguments.
- "Exists already" for MCP save_file and import_iflow is decided by the file system: CREATE_NEW for the file and createDirectory for the iFlow folder. The per-server mutex alone did not reach a second MCP process on the same project.
- Platform numbers beyond 2^53 go to the engine as exact digits however the file writes them (.0, exponent). Json.num reads them exactly and rejects a fraction or a value beyond a long. Before, longValue() cut or wrapped them silently, even from the file.
- The XPath scanner flags any & that does not start a predefined or character reference: entity names can be Unicode. It also flags one inside an xmlns value.
- Router: whether a route was taken is a flag, not "the name is not empty". An empty name is shown as "Route n".
- A Gather's messagesFrom is resolved once, in call(), with the input.

## Tried and ruled out
- Fixing only the dropdown entry point: the typed name bypassed it.
- An ASCII entity-name regex as a completeness check.
- Sending a number's text only for pure integer tokens.

## Watch out
- Monaco decorations appear a frame later. The smoke test now waits for the marks instead of counting them at once; it was flaky.
- With overwrite: true, two parallel saves both write, and `replaced` may be wrong for the second one. This is documented.

In the code: app/src/lib/store.svelte.ts setGroovyFunction; engine/core Json.num, ProjectFiles.write createNew, IflowImport.importZip(…, createNew); app/src/lib/xmltext.ts ENTITY_REF; app/src/lib/platform.ts integerDigits; mcp/flow.rs router `decided`; mcp/tools.rs RESOLVED_MESSAGES.
