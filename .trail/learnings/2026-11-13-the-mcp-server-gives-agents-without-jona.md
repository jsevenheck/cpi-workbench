---
title: "The MCP server gives agents without file tools read, save and iFlow import, and a reference generated from the sources"
author: Jona
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 20, tokens: 199213 }
id: l-601af56e
scope: repo
status: active
src: agent
touches:
  - docs/manual-test.md
  - app/src-tauri/src/mcp/reference.rs
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/src-tauri/src/mcp/mod.rs
  - app/src-tauri/src/mcp/tests.rs
  - docs/test-cases.md
  - docs/fidelity.md
  - engine/core/src/main/java/io/cpiwb/core/ProjectFiles.java
---

## Decided
* The user's goal for MCP is Claude Desktop developing a step the way a person does in the app (open files, write, press Run), not running the repository's test cases. Test cases are regression cases for the app itself and stay out of the MCP server.
* Claude Desktop has no file tools, so the server now reads files (in parts; one over-long line, e.g. minified XML, is cut and says so), saves files and imports iFlow zips. Writes never replace a file or iFlow folder without `overwrite: true`, and are annotated destructive+idempotent so the client asks. Paths that are absolute, have a drive letter or contain `..` are refused in Rust before the engine's own ProjectFiles check.
* The `reference` tool is generated, not written: Message/MessageLog members from completions.ts (already tested against the Java sources), public methods and first Javadoc sentence of the clean-room platform classes, the platform section of docs/test-cases.md and docs/fidelity.md, all via include_str!. A hand-written reference would drift.
* No Rust port of the Groovy tab's function reader: an agent reads the script text itself.

## Tried and ruled out
* A headless Claude run with only the MCP tools (no Read/Bash) on the demo project built a valid mapping, a Content Modifier and saved the file. Its main complaint: steps cannot be chained by reference, so a body is copied from one result into the next call. Left open: a stateless server has no run handles, and writing intermediate bodies into inputs/ leaves files behind.

## Watch out
* validate_xsd always uses the JDK's XSD 1.0 validator; its `runtime` only picks the engine process. The tool description says so now, because the agent took it for a Saxon choice.
* Python's open(..., 'w') on Windows writes CRLF; convert edited files back to LF before committing.

In the code: app/src-tauri/src/mcp/files.rs, reference.rs, prompt and INSTRUCTIONS in mod.rs; tests `files_are_read_saved_and_imported_inside_the_project` (builds a stored zip by hand, no zip crate) and `reference_topics_come_from_the_sources`. Headless check: `claude -p … --mcp-config mcp.json --strict-mcp-config --allowedTools mcp__cpi-workbench --disallowedTools Bash,Read,Write,Edit,…`.
