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
clients without a project folder name the folder the server works in. For several integrations, give a
**workspace**: a folder that holds one project folder per integration (`C:\work\cpi\Orders`,
`C:\work\cpi\Billing`, …), with one entry for all of them:

```json
{
  "mcpServers": {
    "cpi-workbench": {
      "command": "C:\\Users\\<user>\\AppData\\Local\\CPI Workbench\\cpi-workbench.exe",
      "args": ["mcp", "--workspace", "C:\\work\\cpi"]
    }
  }
}
```

The server then starts without a project. `project_info` lists the workspace's folders under `projects`, and
`open_project` opens one of them, or creates a new one with `create: true` (the layout of the app's
*Import iFlow…* without a project). The agent works in one project at a time and never outside the workspace;
ask it, for example, *"Open the project Orders"* or *"Create a project Billing and import C:\Downloads\Billing.zip"*.
For a single project, give `"--project", "C:\\work\\my-cpi-project"` instead of `--workspace` (not both).

**Codex** (`~/.codex/config.toml`):

```toml
[mcp_servers.cpi-workbench]
command = 'C:\Users\<user>\AppData\Local\CPI Workbench\cpi-workbench.exe'
args = ["mcp", "--workspace", 'C:\work\cpi']   # or "--project", 'C:\work\my-cpi-project'
```

In both, replace `<user>` with your Windows user name (the folder under `C:\Users`) and the folder with yours,
then restart the client. With the `.msi`, the executable is `C:\Program Files\CPI Workbench\cpi-workbench.exe`.
The folder must exist; a workspace may start empty.

The project folder is `--project`, else `CLAUDE_PROJECT_DIR`, else the server's working directory; with
`--workspace`, the folder `open_project` opened.
From a repository checkout, a debug build works too (`app/src-tauri/target/debug/cpi-workbench mcp`);
it finds the checkout's engine jars.

## Working from Claude Desktop

With only CPI Workbench configured, Claude Desktop can access the project through this server;
a separate filesystem server is not required. For example, with an
iFlow and a sample message in the project: *"Here is the iFlow Order_Replication and inputs/order.xml. Write the
mapping to the S/4 format and try it."* The agent lists the project (`project_info`), reads the input and the
iFlow's files (`read_file`), writes the mapping and runs it (`run_xslt`) until the output is right, and shows
you the result; for a Content Modifier or Router it shows the settings as you enter them in the iFlow. It saves
a file (`save_file`) when you want to keep it; the app shows it after **↻** in its file tree.

Other requests that work this way:

* *"How does a message go through Order_Replication?"*: `iflow_diagram` gives the steps in order, the
  Router's routes with their conditions and the connected systems.
* *"How must the Content Modifier 'Set headers' be set up to also set the header OrderType from the order's
  type attribute?"*: the agent takes the step's configuration from `project_info`, runs changed copies with
  `run_flow_step` on a sample message until the headers are right, and shows the rows as you enter them.
* *"Which hosts and credentials do the iFlows use?"*: `iflow_where_used` without a query.
* *"Run the iFlow's steps in order on this message"*: each step with the matching tool, the message passed on
  by `runId`.

The prompt **develop_step** (in Claude Desktop under **+**) starts this with a task, an iFlow and an input.

## Tools

The run tools are the app's tabs, with the same inputs and the same engine calls. The file tools give an
agent without file access of its own what the app's file tree and **Import iFlow** give you.

| Tool | Tab in the app |
|---|---|
| `project_info` | The project: its files, the steps of the iFlows under `iflows/` (flow steps with their configuration; script, XSLT and XML Validator steps with file, function and runtime; a script step's `functionFound` says whether its script declares that function). With several iFlows (a package), `iflows` lists each iFlow's steps by name and type, without configuration, and the files under `iflows/` are left out, so the answer stays small; `iflow` gives one iFlow's steps in full (in the export's order; `iflow_diagram` gives the flow order) with only its own files and the script collection files its steps use. Also the installed runtimes; with `--workspace`, also the workspace's folders (`projects`). |
| `open_project` | **Open folder…** / **Import iFlow…** without a project, within the workspace (`--workspace` only): opens a folder directly inside it (`name`), or creates a new project there (`create: true`), and returns its `project_info` (without the runtimes, which do not change). Calls that are running finish on the project they started on; earlier `runId`s stay usable. |
| `read_file` | Opening a file: the text of a project file (a Groovy script's also with the `functions` it declares, read from its text as the Groovy tab does; nothing runs), long files in parts (`startLine`, `maxLines`), text in one long line by characters (`startChar`); a file that is no text with `binary: true` (as `contentBase64`); with `runId`, the whole output body of a run (a binary one as `contentBase64`). |
| `run_xslt` | XSLT (`mappingPath` or `mapping`). `runtime: "both"` runs Saxon-HE 9.9 and 12, as the runtime comparison does. |
| `run_groovy` | Groovy, with the Script API and the project's `platform/` mocks, and the body type (`bodyType`; `InputStream` gives Camel's `InputStreamCache`, as behind a tenant's HTTPS sender, reset after the step so the body passes on whole: [Fidelity](fidelity.md), *Stream body*). The runtime (1.x or 2.x) follows the script's imports unless given; `both` runs both. A custom function of a Message Mapping script takes `args` instead, as the Groovy tab's *Arguments*; the result's `mapping` holds the returned value or the output queue, and `mappingFunctions` the script's Message Mapping functions. |
| `validate_xsd` | XSD. |
| `evaluate_xpath` | XPath (1.0 or 3.1, with namespaces). A node of the body also says where it is (`at`: the element positions from the root, its name, an attribute's name). |
| `run_flow_step` | Flow Steps: a Content Modifier, Filter, Router, Splitter, Gather, converter (JSON ↔ XML, XML ↔ CSV), Encoder, Decoder (Base64, GZIP, ZIP) or Message Digest step from an iFlow (`iflowStep`: name or id, with `iflow` when several iFlows share it) or from a configuration (`step`). For an iFlow step, `step.defaulted` names the choices the iFlow leaves out, which ran with the default (an assumption). A Router's result names the route `taken` and each route's status (`true`, `false`, `not evaluated` after an earlier match, `default`), explained in `statusNote`; the first true condition wins, in the iFlow's route order (an assumption, [Fidelity](fidelity.md), *Router*). |
| `save_file` | **Save as…**: writes a file (`content`, bytes as `contentBase64`, or a run's output with `contentFrom`, binary or not) into the project, creating folders. An existing file is replaced only with `overwrite: true`. |
| `import_iflow` | **Import iFlow**: an iFlow, Value Mapping or integration package exported from the tenant (`.zip`, any path): iFlows into `iflows/<name>/`, value mappings into `platform/value-mappings.json`; returns `kind`, the imported iFlows' steps (with `functionFound` as in `project_info`), `valueMappings`, `skipped` and `kept` (with `overwrite: true`, files of the earlier version that the export does not contain; they are not deleted). An existing folder or value mapping is replaced only with `overwrite: true`. |
| `iflow_where_used` | **Where used**: without `query` or `kind`, the tab's overview (`uses`: the addresses, credentials, queues, key aliases and files the iFlows use, with the number of places and what their `{{parameters}}` resolve to; `iflows`: each iFlow's externalized parameters, their values and uses, and those unused or without a value). With `query` (a host, credential alias, queue, file, `{{parameter}}` or any text) and/or a `kind` (`address`, `credential`, `queue`, `key`, `file`, `parameter`): the adapters, steps and routes that use it, each with a `{{parameter}}` resolved from `parameters.prop` (`resolved`) and a Content Modifier table as its matching `rows` instead of its XML text, plus the `parameters` whose name or value contains the query. `iflow` limits it to one iFlow; `parameters: true` returns only the parameters. A receiver adapter's hit is labelled with the step that calls it (`SOAP receiver from Call S4`). Read-only. |
| `iflow_diagram` | **Diagram**, as text: an imported iFlow's processes (integration process, exception subprocesses, local processes), each with its elements in flow order from the start event, as the sidebar's outline lists them: a Router's routes indented (`depth`) with their name and condition, each element on a route naming it (`onRoute`: router and route), an element where branches meet listed again (`again`), a Process Call with the process it calls. Each element has its type as in the editor and its `settings` as written in the iFlow (`{{parameters}}` unresolved; `project_info` and `iflow_where_used` resolve them); the `adapters` have their system, direction and settings. `iflow` picks the iFlow when there are several. Read-only; the drawing's geometry is left out. |
| `reference` | No tab: what the simulator offers, generated from its sources. `script-api`: the Script API, Message Mapping and platform service methods; `platform`: the formats of the `platform/` files; `fidelity`: [Fidelity](fidelity.md). |

Without `runtime`, a tool uses the app's default (Saxon-HE 9.9; Groovy from the script's imports), or the
other runtime of that kind when the default is not installed, as the app does. Flow steps run Camel Simple
on Camel 3.14.7 with any installed Groovy engine.

A message is `body` (or `bodyPath`, a project file) and, where the tab has them, `headers` and `properties`. A header or
property is a string, or `{"value": "…", "type": "Integer"}` for another type (the types in
[Project files](project-files.md#header-and-property-types)). A property of type `null` is left out with a warning, as
Camel keeps no property with a null value. Results are the engines' JSON, the same data the app shows: the output
message, diagnostics with lines, `xsl:message` output, the message log, timings.

### Passing results on (`runId`)

A run that produces a message returns a `runId`: `run_xslt`, `run_groovy`, `validate_xsd` (the message
passes on, as the XML Validator lets it), `run_flow_step` (the Router passes the message on, unless it raises an
exception: then `ok` is false; a Splitter's
messages are `runId/1`, `runId/2`, …; with `runtime: both`, `runId/saxon-he-12` and so on). A failed run and an
invalid message get none. Each of these messages also carries its own id as `runId` in the result (a
Splitter's `messages[]`, the entries of `runs[]`). The input (with a Gather's `messagesFrom`) is read once, before the step runs, so the
kept message is the one the step got. `messageFrom: "<runId>"` makes that message (body, headers, properties) the input of
the next call, as it flows to the next step in an iFlow; `body`, `headers` and `properties` given in the same call
replace the body or the rows of the same name. A Gather takes `messagesFrom` (a Splitter's `runId`, or a list).

When steps run one after another, `brief: true` on `run_xslt`, `run_groovy` and `run_flow_step` shortens each
result to what changed: of the output's headers and properties only those that differ from the input (a result's
`changes` also names removed ones), without engine, timing, trace and empty lists. The run keeps the whole message
for `messageFrom`.

To keep results small, a body the step left as it was (from 200 characters) is left out, with
`bodyUnchanged` saying so; bodies longer than 20,000 characters, and a Splitter's messages longer than 2,000,
come back shortened, with `bodyShortened`; a long binary body is left out. `messageFrom` still passes it on
as bytes, as `bodyPath` does with a `.zip` or `.gz` file. A Content Modifier with an empty Message Body
preserves those bytes along with its header and property changes. The run keeps the whole body:
`read_file` with `runId` reads it, `save_file` with `contentFrom` saves it. The server keeps the
last 50 runs, up to 64 MiB of retained message data. A run too large to fit still returns its result,
shortened, but without a `runId`: `runNotice` (starting with `UnsupportedInSimulator`) says so, and older
runs stay. A tool response whose JSON preview would exceed 1 MiB has its longest lists cut (an imported
iFlow's `steps`, for instance), with `resultShortened` naming what was cut; the call itself, such as an
import's file writes, has completed. A result that cannot be cut down is an error that says the call
completed, names the `runId` of a retained run and, for `save_file` and `import_iflow`, that the files were
written.

Calls can run concurrently. Without `overwrite`, file/iFlow creation is checked atomically by the
file system, so another server on the same project cannot replace an existing destination either.
With `overwrite`, concurrent writes are not a transaction; avoid writing the same path from two
sessions. The `replaced` flag is informational and may be stale under concurrent overwrites.

Wrong arguments, files outside the project and engine failures come back as tool results with
`isError: true` and a message, so the agent can correct the call. A failing mapping or script is a
normal result with `ok: false` and its diagnostics. Messages that start with
`UnsupportedInSimulator` name behaviour the simulator does not have; [Fidelity](fidelity.md)
lists what is verified against CPI.

## What the server can access

* **Files:** only the project folder (with `--workspace`, the project `open_project` opened; it opens or
  creates only folders directly inside the workspace, and refuses a link that leads outside it). Bodies, mappings, scripts and schemas are read through the
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
  `read_file`, `validate_xsd`, `evaluate_xpath`, `iflow_where_used` and `reference` are marked read-only and closed-world;
  `save_file` and `import_iflow` are marked as writing (destructive, idempotent, closed-world); `open_project` as
  not read-only but not destructive (it creates a folder at most). A client that
  uses these hints can ask before the others run.
* **Network:** MCP opens no port, starts no CPI Helper receiver and makes no external requests.
  XML runs resolve includes, documents
  and entities in the project only, so they fetch nothing from the network; XPath refuses
  `fn:transform()`, which could escape that. Reflexive `java:` calls and `fn:transform()` in XSLT,
  and Groovy scripts, are not restricted.
* **Input from files:** message bodies and test data may contain text written to steer an agent
  (prompt injection). The server passes results through as they are.

## Protocol

* Transport: stdio, one JSON-RPC message per line (input frames up to 32 MiB). Logs go to stderr.
* Up to eight requests can be running or waiting for the writer, in addition to the one reply the
  writer may already be sending. An excess request gets a busy error and does not run. Replies use a
  bounded queue; a client that continually submits while not reading may lose the session rather than
  accumulate replies. Cancellation can drop a queued reply independently of stdout. The writer releases
  the request ID before sending the reply, so the client can reuse it as soon as it reads that reply.
* Revisions: dual-era as the [2026-07-28 specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
  describes it. Requests that carry `_meta["io.modelcontextprotocol/protocolVersion"]` are served
  statelessly under 2026-07-28 (`server/discover`, `resultType`, caching hints on lists). A client
  that opens with `initialize` gets 2025-11-25, 2025-06-18, 2025-03-26 or 2024-11-05.
* Features: tools, with `structuredContent` and the same JSON as text; one prompt (`develop_step`);
  `instructions` that tell the agent how to work with the tools. No resources, logging, sampling or roots. `notifications/cancelled` drops the answer; the engine finishes the run.
* Engines start on first use and stop after 10 idle minutes (Groovy); an engine that hits the
  30-second execution timeout restarts; every call carries its project, as the desktop app's calls do. The supervisor also bounds queue
  wait, startup and pipe I/O independently (see [Architecture](architecture.md#engines-and-protocol)).
  The server stops when its stdin closes.
* Tested with the official TypeScript SDK 1.32.2 and Python SDK 2.3.0 clients (both negotiate
  2025-11-25) and, for 2026-07-28, with the repository's own tests (`app/src-tauri/src/mcp/tests.rs`,
  `app/scripts/mcp-smoke.mjs`, which CI runs on the release build on Windows and macOS).

The code is in `app/src-tauri/src/mcp/`. `flow.rs` repeats the Flow Steps tab's calls
(`app/src/lib/flowcalls.ts`); the fixtures in `testdata/flow-parity` hold the calls both must make for a
step's settings and message, and a test on each side replays them without engines. Content Modifier
fixtures also check results and early failures. They do not cover how the
tab's editors or the MCP tool's arguments become those settings and that message. In the same way,
`whereused.rs`, `functions.rs` and `iflowmodel.rs` repeat what the Where used tab, the Groovy tab's function list
and the Diagram tab compute in TypeScript (`whereused.ts`, `functions.ts`, `iflowmodel.ts`), checked by
`testdata/whereused-parity`, `testdata/functions-parity` and `testdata/iflowmodel-parity`.
