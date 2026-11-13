# MCP server for coding agents

`cpi-workbench mcp` runs a [Model Context Protocol](https://modelcontextprotocol.io) server over
stdio. An agent such as Claude Code or Claude Desktop can use it to run an XSLT mapping, a Groovy script or a
flow step on the simulator while it writes the code, the way you press **Run** in the app. The server uses the
same engines and the same calls as the desktop app, offline.

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
so the file can be committed with the project. On macOS, use the path from the table. Claude
Code asks once before it starts the servers of a project's `.mcp.json`. Instead of the file, one
command adds the server for you alone: `claude mcp add cpi-workbench -- <executable> mcp`.

**Claude Desktop** (*Settings → Developer → Edit Config*, `claude_desktop_config.json`) and other
clients without a project folder need `--project`:

```json
{
  "mcpServers": {
    "cpi-workbench": {
      "command": "C:\\Users\\<user>\\AppData\\Local\\CPI Workbench\\cpi-workbench.exe",
      "args": ["mcp", "--project", "C:\\work\\my-cpi-project"]
    }
  }
}
```

**Codex** (`~/.codex/config.toml`):

```toml
[mcp_servers.cpi-workbench]
command = 'C:\Users\<user>\AppData\Local\CPI Workbench\cpi-workbench.exe'
args = ["mcp", "--project", 'C:\work\my-cpi-project']
```

In both, replace `<user>` with your Windows user name (the folder under `C:\Users`) and the project
path with yours, then restart the client.

The project folder is `--project`, else `CLAUDE_PROJECT_DIR`, else the server's working directory.
From a repository checkout, a debug build works too (`app/src-tauri/target/debug/cpi-workbench mcp`);
it finds the checkout's engine jars.

## Working from Claude Desktop

Claude Desktop has no access to your files of its own; the server gives it the project. For example, with an
iFlow and a sample message in the project: *"Here is the iFlow Order_Replication and inputs/order.xml. Write the
mapping to the S/4 format and try it."* The agent lists the project (`project_info`), reads the input and the
iFlow's files (`read_file`), writes the mapping and runs it (`run_xslt`) until the output is right, and shows
you the result; for a Content Modifier or Router it shows the settings as you enter them in the iFlow. It saves
a file (`save_file`) when you want to keep it; the app shows it after **↻** in its file tree.

The prompt **develop_step** (in Claude Desktop under **+**) starts this with a task, an iFlow and an input.

## Tools

The run tools are the app's tabs, with the same inputs and the same engine calls. The file tools give an
agent without file access of its own what the app's file tree and **Import iFlow** give you.

| Tool | Tab in the app |
|---|---|
| `project_info` | The project: its files, the steps of the iFlows under `iflows/` (flow steps with their configuration; script, XSLT and XML Validator steps with file, function and runtime) and the installed runtimes. |
| `read_file` | Opening a file: the text of a project file, long files in parts (`startLine`, `maxLines`). |
| `run_xslt` | XSLT (`mappingPath` or `mapping`). `runtime: "both"` runs Saxon-HE 9.9 and 12, as the runtime comparison does. |
| `run_groovy` | Groovy, with the Script API and the project's `platform/` mocks, and the body type (`bodyType`). The runtime (1.x or 2.x) follows the script's imports unless given; `both` runs both. A custom function of a Message Mapping script takes `args` instead, as the Groovy tab's *Arguments*; the result's `mapping` holds the returned value or the output queue. |
| `validate_xsd` | XSD. |
| `evaluate_xpath` | XPath (1.0 or 3.1, with namespaces). |
| `run_flow_step` | Flow Steps: a Content Modifier, Filter, Router, Splitter, Gather or converter (JSON ↔ XML, XML ↔ CSV) step from an iFlow (`iflowStep`: name or id, with `iflow` when several iFlows share it) or from a configuration (`step`). |
| `save_file` | **Save as…**: writes a text file into the project, creating folders. An existing file is replaced only with `overwrite: true`. |
| `import_iflow` | **Import iFlow**: an iFlow exported from the tenant (`.zip`, any path) into `iflows/<name>/`; returns its steps. An existing folder is replaced only with `overwrite: true`. |
| `reference` | No tab: what the simulator offers, generated from its sources. `script-api`: the Script API, Message Mapping and platform service methods; `platform`: the formats of the `platform/` files; `fidelity`: [Fidelity](fidelity.md). |

Without `runtime`, a tool uses the app's default (Saxon-HE 9.9; Groovy from the script's imports), or the
other runtime of that kind when the default is not installed, as the app does. Flow steps run Camel Simple
on Camel 3.14.7 with any installed Groovy engine.

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
  The check follows symbolic links: a link inside the project that leads outside it is refused.
  Paths that are absolute or contain `..` are refused before the engine sees them.
  Only `save_file` and `import_iflow` write into the project, and they replace nothing without
  `overwrite: true`; `import_iflow` reads the zip from the path it is given, also outside the project.
  The engines keep caches in the temp folder and the user's cache folder, as in the app. Groovy
  scripts can write files (next point).
* **Code:** Groovy scripts execute as ordinary Java code with your user's rights, without a sandbox;
  Camel Simple in flow steps and reflexive `java:` calls in XSLT can call methods too. An agent that can
  call `run_groovy` or `run_xslt` can run any code. The MCP annotations say so: only `project_info`,
  `read_file`, `validate_xsd`, `evaluate_xpath` and `reference` are marked read-only and closed-world;
  `save_file` and `import_iflow` are marked as writing (destructive, idempotent, closed-world). A client that
  uses these hints can ask before the others run.
* **Network:** the server opens no port and connects nowhere. XML runs resolve includes, documents
  and entities in the project only, so they fetch nothing from the network; XPath refuses
  `fn:transform()`, which could escape that. Reflexive `java:` calls and `fn:transform()` in XSLT,
  and Groovy scripts, are not restricted.
* **Input from files:** message bodies and test data may contain text written to steer an agent
  (prompt injection). The server passes results through as they are.

## Protocol

* Transport: stdio, one JSON-RPC message per line. Logs go to stderr.
* Revisions: dual-era as the [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
  describes it. Requests that carry `_meta["io.modelcontextprotocol/protocolVersion"]` are served
  statelessly under 2026-07-28 (`server/discover`, `resultType`, caching hints on lists). A client
  that opens with `initialize` gets 2025-11-25, 2025-06-18, 2025-03-26 or 2024-11-05.
* Features: tools, with `structuredContent` and the same JSON as text; one prompt (`develop_step`);
  `instructions` that tell the agent how to work with the tools. No resources, logging, sampling or roots. `notifications/cancelled` drops the answer; the engine finishes the run.
* Engines start on first use and stop after 10 idle minutes (Groovy); an engine that hits the
  30-second timeout restarts with the project open again. The server stops when its stdin closes.
* Tested with the official TypeScript SDK 1.32.2 and Python SDK 2.3.0 clients (both negotiate
  2025-11-25) and, for 2026-07-28, with the repository's own tests (`app/src-tauri/src/mcp/tests.rs`,
  `app/scripts/mcp-smoke.mjs`, which CI runs on the release build on Windows and macOS).

The code is in `app/src-tauri/src/mcp/`. `flow.rs` follows the Flow Steps tab
(`app/src/lib/store.svelte.ts`) call for call; change the two together.
