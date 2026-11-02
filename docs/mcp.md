# MCP server for coding agents

`cpi-workbench mcp` runs a [Model Context Protocol](https://modelcontextprotocol.io) server over
stdio. A coding agent such as Claude Code can use it to run an XSLT mapping, a Groovy script or a
flow step on the simulator while it writes the code. The server uses the same engines and the same
calls as the desktop app, offline.

## Setup

The installed app brings the server. Any MCP client that starts local (stdio) servers can use it,
for example Claude Code (terminal or the Code tab of the Claude desktop app), Claude Desktop or
Codex. The client starts `cpi-workbench mcp` itself, in the background: the app window does not
need to be open, and runs by the agent do not appear in it. The two share only the project's files.

| OS | Executable (default install location) |
|---|---|
| Windows, `setup.exe` | `%LOCALAPPDATA%\CPI Workbench\cpi-workbench.exe` |
| Windows, `.msi` | `C:\Program Files\CPI Workbench\cpi-workbench.exe` |
| macOS | `/Applications/CPI Workbench.app/Contents/MacOS/cpi-workbench` |
| Linux (`.deb`, `.rpm`) | `/usr/bin/cpi-workbench` |

**Claude Code:** a `.mcp.json` in the project folder (Windows, `setup.exe`):

```json
{
  "mcpServers": {
    "cpi-workbench": {
      "command": "${LOCALAPPDATA}\\CPI Workbench\\cpi-workbench.exe",
      "args": ["mcp"]
    }
  }
}
```

Claude Code expands `${LOCALAPPDATA}` and tells the server its project folder (`CLAUDE_PROJECT_DIR`),
so the file can be committed with the project. On macOS or Linux, use the path from the table. Claude
Code asks once before it starts the servers of a project's `.mcp.json`. Instead of the file, one
command adds the server for you alone: `claude mcp add cpi-workbench -- <executable> mcp`.

**Claude Desktop** (*Settings → Developer → Edit Config*, `claude_desktop_config.json`) and other
clients without a project folder need `--project`:

```json
{
  "mcpServers": {
    "cpi-workbench": {
      "command": "C:\\Users\\me\\AppData\\Local\\CPI Workbench\\cpi-workbench.exe",
      "args": ["mcp", "--project", "C:\\work\\my-cpi-project"]
    }
  }
}
```

**Codex** (`~/.codex/config.toml`):

```toml
[mcp_servers.cpi-workbench]
command = 'C:\Users\me\AppData\Local\CPI Workbench\cpi-workbench.exe'
args = ["mcp", "--project", 'C:\work\my-cpi-project']
```

The project folder is `--project`, else `CLAUDE_PROJECT_DIR`, else the server's working directory.
From a repository checkout, a debug build works too (`app/src-tauri/target/debug/cpi-workbench mcp`);
it finds the checkout's engine jars.

## Tools

The tools are the app's tabs, with the same inputs and the same engine calls. The app leads: the
server adds no function of its own.

| Tool | Tab in the app |
|---|---|
| `project_info` | The project: its files, the steps of the iFlows under `iflows/` (flow steps with their configuration; script, XSLT and XML Validator steps with file, function and runtime) and the installed runtimes. |
| `run_xslt` | XSLT (`mappingPath` or `mapping`). `runtime: "both"` runs Saxon-HE 9.9 and 12, as the runtime comparison does. |
| `run_groovy` | Groovy, with the Script API and the project's `platform/` mocks, and the body type (`bodyType`). The runtime (1.x or 2.x) follows the script's imports unless given; `both` runs both. |
| `validate_xsd` | XSD. |
| `evaluate_xpath` | XPath (1.0 or 3.1, with namespaces). |
| `run_flow_step` | Flow Steps: a Content Modifier, Filter, Router, Splitter or Gather step from an iFlow (`iflowStep`: name or id) or from a configuration (`step`). |

A message is `body` (or `bodyPath`, a project file) and, where the tab has them, `headers` and `properties`. A header or
property is a string, or `{"value": "…", "type": "Integer"}` for another type (the types of
[test cases](test-cases.md)). Results are the engines' JSON, the same data the app shows: the output
message, diagnostics with lines, `xsl:message` output, the message log, timings.

Wrong arguments, files outside the project and engine failures come back as tool results with
`isError: true` and a message, so the agent can correct the call. A failing mapping or script is a
normal result with `ok: false` and its diagnostics. Messages that start with
`UnsupportedInSimulator` name behaviour the simulator does not have; [Fidelity](fidelity.md)
lists what is verified against CPI.

## What the server can access

* **Files:** only the project folder. Bodies, mappings, scripts and schemas are read through the
  engines, which refuse paths outside the project, as in the app ([What a run can access](user-guide.md#what-a-run-can-access)).
  The check compares paths, so a symbolic link inside the project can point outside it.
  The tools write nothing into the project; the engines keep caches in the temp folder and the
  user's cache folder, as in the app. Groovy scripts can write files (next point).
* **Code:** Groovy scripts execute as ordinary Java code with your user's rights, without a sandbox;
  Camel Simple in flow steps and reflexive `java:` calls in XSLT can call methods too. An agent that can
  call `run_groovy` or `run_xslt` can run any code. The MCP annotations say so: only `project_info`,
  `validate_xsd` and `evaluate_xpath` are marked read-only and closed-world; a client that uses these
  hints can ask before the others run.
* **Network:** the server opens no port and connects nowhere. XML runs block the network; Groovy
  scripts are not restricted.
* **Input from files:** message bodies and test data may contain text written to steer an agent
  (prompt injection). The server passes results through as they are.

## Protocol

* Transport: stdio, one JSON-RPC message per line. Logs go to stderr.
* Revisions: dual-era as the [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
  describes it. Requests that carry `_meta["io.modelcontextprotocol/protocolVersion"]` are served
  statelessly under 2026-07-28 (`server/discover`, `resultType`, caching hints on lists). A client
  that opens with `initialize` gets 2025-11-25, 2025-06-18, 2025-03-26 or 2024-11-05.
* Features: tools only, with `structuredContent` and the same JSON as text. No resources, prompts,
  logging, sampling or roots. `notifications/cancelled` drops the answer; the engine finishes the run.
* Engines start on first use and stop after 10 idle minutes (Groovy); an engine that hits the
  30-second timeout restarts with the project open again. The server stops when its stdin closes.
* Tested with the official TypeScript SDK 1.32.2 and Python SDK 2.3.0 clients (both negotiate
  2025-11-25) and, for 2026-07-28, with the repository's own tests (`app/src-tauri/src/mcp/tests.rs`,
  `app/scripts/mcp-smoke.mjs`, which CI runs on the release build on Windows, macOS and Linux).

The code is in `app/src-tauri/src/mcp/`. `flow.rs` follows the Flow Steps tab
(`app/src/lib/store.svelte.ts`) call for call; change the two together.
