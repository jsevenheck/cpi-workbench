---
title: "Platform tables in the Groovy tab edit the project's JSON files and send unsaved rows with each run"
author: Claude
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 10, tokens: 124561 }
id: l-66521ab9
scope: repo
status: active
src: agent
touches:
  - app/src/lib/platform.ts
  - app/src/components/RowTable.svelte
  - app/src/lib/store.svelte.ts
  - app/e2e/groovy.mjs
---

## Decided
- The Platform tab (value mappings, Secure Store, Partner Directory parameters, number ranges) is a table view over platform/*.json; the files stay the source of truth, so the CLI, test cases and the MCP server needed no change and the engines were not touched.
- A table with unsaved changes goes with every run as the `platform` override that test cases already use (it replaces a file key by key), so a run always sees what the table shows; Save writes the file. Without a project the tables work in memory the same way.
- Whatever a table does not show is kept on save: a credential's properties and OAuth2 fields (and an absent username/password stays absent), binary Partner Directory parameters, alternative partners, authorized users, unknown members. The keystore stays a file.
- Attachments and SOAP headers became normal inputs of the Groovy tab (rows in `groovy.attachments` / `groovy.soapHeaders`), no longer hidden "case extras". A case's attachment file is turned into a project-relative path on load, so the request needs no caseDir.
- Opening a project replaces rows typed without one (after a question): otherwise they would look like unsaved changes of files they never came from, and Save would overwrite the project's file.

## Watch out
- Save rewrites a platform file as indented JSON; a hand-formatted file loses its layout.
- A test case's own `platform` object wins over the tables for its runs; the tab says so per section.
- A bindable prop given `undefined` must have no default (see the queue editor); RowTable's rows always exist.

In the code: app/src/lib/platform.ts (rows ⇄ document, tests in app/tests/platform.test.mts); `platform`, loadPlatform, savePlatform, platformOverride in app/src/lib/store.svelte.ts; PlatformPanel.svelte, RowTable.svelte; e2e 4a1/4a2 in app/e2e/groovy.mjs.
