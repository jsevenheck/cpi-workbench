---
title: "Binary bodies travel as bodyBase64 through the tab, the MCP server and Groovy; a script gets bytes that are no text as byte[]"
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 20, tokens: 317508 }
id: l-906a104f
scope: repo
status: active
src: agent
touches:
  - engine/native/workload.py
  - app/src-tauri/src/mcp/runs.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src-tauri/src/mcp/flow.rs
  - app/src/lib/store.svelte.ts
  - engine/groovy/src/main/java/io/cpiwb/groovy/ScriptRunner.java
  - engine/groovy/src/test/java/io/cpiwb/groovy/ScriptRunnerTest.java
  - probes/README.md
---

## Decided
- W4 stage 2 finished on 2026-11-16 (Windows, the user's machine). A step result that is no text is `bodyBase64` + `binary: true`; the Flow Steps tab keeps such an input in `flow.binary` (Base64 and size) and sends `bodyBase64` instead of `body`. Everything that sets the text body clears it (`setFlowBody`).
- The MCP server's `runs::Message` has an optional `body_base64`; `Message::to_json` gives the engines either form, so `messageFrom`, `messagesFrom`, `bodyPath` (a file that is no text is read with `binary: true`) and `save_file contentFrom` keep bytes. `read_file` returns bytes only with `binary: true` (path) or for a binary run, and refuses more than 200,000 Base64 characters instead of cutting them.
- A binary body longer than the preview limit is left out of a result with a `bodyShortened` pointer; a short one stays.
- Gather of binary messages is refused (`UnsupportedInSimulator`), not passed as empty text.
- The Content Modifier's intermediate state and the splitter's body-only input keep `bodyBase64` in both the tab and `mcp/flow.rs`, so a binary input does not silently become an empty body.

## Tried and ruled out
- Leaving the Groovy engine's input as it was: the probe baseline showed the GZIP bytes garbled (`1f 8b` became `1f ef bf bd`), because `bodyType` defaults to String and the bytes were decoded with replacement. Now a body that is no text goes in as `byte[]` with an info; which class CPI gives it is Q-E1's to answer.
- Making a Groovy script's binary result `bodyBase64` too: it would change the Groovy tab and every Groovy result reader. It stays text, with an `UnsupportedInSimulator` warning when the bytes do not survive.
- A hand-written Base64 encoder in Rust for `read_file binary` on a text run: no base64 crate in the shell, and nothing needed it.

## Watch out
- Writing Python edit scripts through a bash heredoc in this environment ate backslash-newline pairs and turned `\r\n` escapes into real line breaks; write such scripts to a file with the Write tool instead.
- The ZIP baseline is only with `CamelFileName`: without it the simulator makes up the message id, and a ZIP archive's bytes hold the time of the run anyway.
- `gh` is not installed on the user's Windows machine and the unauthenticated GitHub API was rate limited, so the PR description could not be updated from the session.

In the code: `flow.binary`, `setFlowBody`, `setBinaryInput`, `readInputFile`, `writeBytes`, `useAsFlowInput` in `app/src/lib/store.svelte.ts`; `FlowStepsView.svelte` (Encoder / Decoder block, binary input pane); `runs::Message`, `shorten` in `mcp/runs.rs`; `read_body`, `message` in `mcp/tools.rs`; `read_file`, `save_file`, `bytes` in `mcp/files.rs`; `body_only` in `mcp/flow.rs`; `ScriptRunner.run` / `describeOutput` in `engine/groovy`; `probes/encoders/baseline.mjs`.
