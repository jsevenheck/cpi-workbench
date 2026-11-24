---
title: "The MCP server mirrors the app: a workspace of projects, and Where used and the script function reader through parity fixtures"
author: Claude
date: 2026-11-24
branch: claude/optimistic-dijkstra-ek2iek
cost: { minutes: 58, tokens: 331772 }
id: l-8f03408f
scope: repo
status: active
replaces: l-be545910
src: agent
touches:
  - AGENTS.md
  - CHANGELOG.md
  - app/src-tauri/Cargo.lock
  - app/src-tauri/Cargo.toml
  - app/src-tauri/src/mcp/files.rs
  - app/src-tauri/src/mcp/functions.rs
  - app/src-tauri/src/mcp/mod.rs
  - app/src-tauri/src/mcp/tests.rs
  - app/src-tauri/src/mcp/tools.rs
  - app/tests/functionparity.test.mts
---

## Decided
* User (2026-11-24): what the app can do, the MCP server should do too, with the same engine calls. The app leads. This reverses the 2026-11-13 choice not to port the Groovy tab's function reader.
* `--workspace <folder>` serves its direct subfolders; `open_project` opens one or creates one (create + init, like Import iFlow without a project). Engine calls carry the open project (`project.request` with `projectRoot`), so switching needs no engine restart; open_project takes an exclusive lock, other tool calls a shared one.
* Logic the UI computes in TypeScript is repeated in Rust and checked by a fixture both sides replay: Where used (`whereused.rs`, testdata/whereused-parity) and the function reader (`functions.rs`, testdata/functions-parity, expected values generated from functions.ts). The function reader uses the `regex` crate (already in the build via tauri-utils) with the TypeScript patterns, ASCII classes spelled out because JavaScript's `\w` and `\b` are ASCII.
* Where used's overview sorts ignoring case, then by code point, on both sides: `localeCompare` follows the system language and cannot be repeated in Rust.
* User (2026-11-24): an agent should give an input and try how a step (Content Modifier first) would have to be configured, for every step type the app has. That already works: run_flow_step takes `step` {type, config}, so the agent copies an iFlow step's config from project_info, changes it and runs it on the same input; run_groovy/run_xslt/validate_xsd take the source inline. The instructions and descriptions now say so instead of adding a tool.
* Tool descriptions must stand alone: no "as in the tab", no "↻". New texts describe what the simulator does and claim no CPI behaviour (fidelity claims need evidence).
* Found while testing a real package from Claude Desktop (2026-11-24): a Groovy script that read an InputStream body (JsonSlurper) left the next step an empty body. The engine now gives Camel's InputStreamCache (the class a tenant's HTTPS sender hands on, F49) and resets it before reading the output, as Camel 3.14.7 does before and after each processor. Checked in the bytecode of CamelInternalProcessor$StreamCachingAdvice (both before and after call StreamCache.reset). Fidelity row "Stream body", basis Assumption.
* project_info with several iFlows lists steps without config and drops files under iflows/; `iflow` gives one iFlow. The iFlow list comes from the .iflw files, because an iFlow with only adapter calls has no runnable steps.

## Tried and ruled out
* open_project with any path: the project boundary would be gone. MCP roots: not checked in Claude Desktop, and no less configuration.
* Listing functions by compiling the script for read_file: compiling Groovy can run code (AST transformations); reading must not.
* Treating contentModifier.advance/discard as an app feature to port: they are the engine calls a Content Modifier with XPath rows makes between rows (flowcalls.ts, flow.rs), already in the MCP server.

## Watch out
* A headless agent run (claude -p, --strict-mcp-config, only mcp__cpi-workbench) costs about 0.3 USD and 40 s and finds the friction; reports mix real issues with fixture quirks (the iflow test fixture lacks gone.gsh and the `enrich` function on purpose) and unverified CPI claims.
* The engine's search matches the raw property text: in a table a quote is `&apos;`, so a query with `'` does not hit it.
* Piping requests and closing stdin at once drops running tool calls; keep stdin open when trying the binary by hand.

In the code: app/src-tauri/src/mcp/{mod,files,tools,whereused,functions}.rs; app/tests/{whereparity,functionparity}.test.mts; ScriptRunner.stepFunctions.
* To check descriptions, run a headless agent told to rely only on the server's instructions, descriptions, results and errors, and to rate each tool it used; it names what it had to guess. Its claims about CPI or the iFlow are not verified.
* `cargo test` does not rebuild target/debug/cpi-workbench; run `cargo build` before trying the binary or an agent run, or the tool list is stale.

In the code: app/src-tauri/src/mcp/{mod.rs INSTRUCTIONS and prompt, tools.rs definitions}; flowcalls.ts runContentModifier.
* engine/core RpcHttpTest fails now and then in a different HTTP-limit test each time (about 1 in 5 runs here), also without any change. `mvn verify` then stops at core and never tests the Groovy modules: rerun with -Dtest='!RpcHttpTest' -Dsurefire.failIfNoSpecifiedTests=false to see the rest.
* Bodies of real exports go through the MCP results into the agent's chat; the agent found plaintext passwords in parameters.prop of a customer package.

In the code: ScriptRunner.inputBody and describeOutput; ScriptRunnerTest.aStreamBodyReadByTheScriptIsPassedOnWhole; tools.rs project_info.
