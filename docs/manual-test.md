# Manual test before a release

CI covers the engine tests, the browser e2e, the installer builds and the release executable as an
MCP server (`app/scripts/mcp-smoke.mjs`). It does not cover installing the app, using the real
desktop window or a real MCP client. This page is the hand check for that, written for Windows; on
macOS the steps are the same apart from section 1 and the paths.

Sections 1–3 and 5 are required, 4 and 6 optional; about 1 to 1.5 hours. Tenant-only checks require
CPI Helper and a test tenant; record them as not run when unavailable, rather than passed. For each
failure, note the step, expected/actual behaviour and error text or screenshot. Do not release with
a required failure. Keep dated results in the release/PR record, not in this reusable checklist.

## 0. Preparation

**Installers.** Workflow artifacts expire after 3 days ([CI and installers](building.md#ci-and-installers)).
If the last run is older, start a new one: *Actions* → *build* → *Run workflow* on the branch to
test (about 20 minutes). Download `cpi-workbench-windows-latest` from the run's *Artifacts*; it holds
the `…_x64-setup.exe` and the `.msi`. The file name carries the version in `tauri.conf.json`.

**Projects.** From a checkout or a ZIP of the branch you need:

* `examples/demo-project`, to try the tabs;
* `testdata/regression-project`, for the MCP smoke test;
* `engine/core/src/test/resources/iflow`, a test iFlow: zip the folder's contents (`META-INF`, `src`),
  not the folder itself.

Work on **copies** of the project folders: the app and an agent write into them. Installing over a
previous version is a useful test; uninstalling first is not needed.

## 1. Installation (required)

| # | Step | Expected |
|---|---|---|
| 1.1 | Run `…_x64-setup.exe` | SmartScreen warns ("Windows protected your PC") → *More info* → *Run anyway*. No admin prompt. |
| 1.2 | Finish the installer | Installed to `%LOCALAPPDATA%\CPI Workbench\`; Start menu entry present. |
| 1.3 | Start the app | The window opens without an error. |
| 1.4 | Check `%LOCALAPPDATA%\CPI Workbench\cpi-workbench.exe` | Present. The MCP configuration uses this path. |

Optional: the `.msi` instead. It needs admin rights and installs to `C:\Program Files\CPI Workbench\`.
Do not install both at once.

## 2. App with the demo project (required)

Open the copy of `examples/demo-project`. Clicking a file in `inputs/` loads it as the input body.
For rows using `tests/…`, open the copy of `testdata/regression-project` instead; the demo has no tests.
Opening Groovy scripts/cases waits for **Run** or **Ctrl+Enter** before the first execution.

### XSLT

| # | Step | Expected |
|---|---|---|
| 2.0 | Before opening the project: **Examples…** → each entry | The XSLT example runs on its input at once; a Groovy example loads its script and input and runs on Ctrl+Enter, on 2.x. |
| 2.1 | `mappings/orders-to-batch.xsl`, input `inputs/orders.xml` | Output appears (live or Ctrl+Enter) and matches `inputs/order-batch.xml`. |
| 2.2 | `mappings/order-to-salesorder.xsl`, input `inputs/order.xml`, header `SenderSystem`, property `CustomerGroup` | The output uses both values; **Parameters** shows them bound from header and property. |
| 2.3 | **Compare** | Runs on Saxon-HE 9.9 and 12 and lists the differences, or none. |
| 2.4 | Type an error into the mapping | The error is marked at its line; nothing crashes. |
| 2.5 | **Format** | Re-indents; the output stays the same. |
| 2.5a | Right-click in the mapping editor; press **F1**; open a JSON input and then another mapping | Monaco's context menu and command palette open; the editors offer the same features before and after the JSON file. |

### Groovy

The first run starts the Java engines and can take a few seconds.

| # | Step | Expected |
|---|---|---|
| 2.6 | `scripts/enrich-order.groovy` (1.x), input `inputs/order.xml` | Runs. **MPL**, **Console** and **API calls** show entries (Value Mapping, Secure Store from `platform/`). |
| 2.7 | `scripts/validate-order-batch.groovy` (2.x), input `inputs/order-batch.xml` | Runs and sets headers and properties. |
| 2.8 | `scripts/validate-order-batch-json.groovy`, input `inputs/order-batch.json` | Runs with the JSON body. |
| 2.9 | `scripts/route-by-partner.groovy`, input `inputs/order.xml`, header `SalesChannel` = `web-eu` | Reads the Partner Directory. |
| 2.10 | **Compare** on a script | Runs on 1.x and 2.x. |
| 2.10b | Run a mapping, change it so the output differs, run again; output tab **History**, *Compare with the previous run* | Two runs with time, file, runtime and status; the comparison names the body and header differences between the shown result and the earlier run. Running the unchanged mapping again adds no row. |
| 2.11 | Type `message.` in a script; also `messageLogFactory.` and Ctrl+Space | The Script API methods are listed. |
| 2.11a | `scripts/mapping-functions.groovy`: open **Function**, choose `countPerContext(values)`; context 1 starts empty: **+ Value** `A`, **+ Value** `B`, then **+ Context** and `C` | Before any run, **Function** lists the functions under *Message Mapping function* and the left pane shows **Function arguments** with `values`; **Result** shows *Context 1* `2` and *Context 2* `1` (**Text**: `["2"]` and `["1"]`). |
| 2.11b | **Function** `getHeader(headerName)`, `headerName` `SenderSystem` with that header set; then a name that is not set; then **null** | **Result** shows the header's value, then `null`. With **null** the run fails with a `NullPointerException` in line 7: once a header is set, Camel's case-insensitive header map takes no null name. Opening another script brings the **Body** tab back. |
| 2.11c | **⚙** (bottom left) → **Check for updates** | The installed version is shown and compared with the current latest GitHub release; with a newer release, **Open release page** opens it in the browser. If GitHub refuses (e.g. 403), the reason is shown and **Open releases page** opens the list. |
| 2.11e | `tests/groovy-enrich-order/case.json`, run it; **Platform** tab: change *Target value* of the first value mapping; then **Save** | The output's `SoldTo` property follows the change at once, with a dot next to *Value mappings*; after **Save** the dot is gone and `platform/value-mappings.json` has the value. |
| 2.11f | `tests/groovy-attachment/case.json`: **Attachments** tab; set *Content from* to *text*, name `delivery-note.txt`, content `Delivery DN-1` | The tab shows the case's file with its project path; with the text, the `DeliveryNote` header is `Delivery DN-1`. |
| 2.11g | With CPI Helper on a tenant's iFlow page, trace on: send a Groovy step from the **GroovyDebugX** plugin (body, headers, properties, script); copy the opened tab's address; Groovy tab → **Import…** → paste | Body, headers, properties and the script arrive; the status line says what came and that the script has not run; **Run** runs it. |
| 2.11j | **⚙** → **Receive links from CPI Helper** on (port 7790 on a fresh installation; a setting saved by 0.4.0 keeps 7778); set the shown *Custom URL* in GroovyDebugX and xsltDebugX; send a Groovy step, then an XSLT step | The plugin's tab says "Sent. Confirm the import…"; the app comes to the front with a dialog naming the tab, the body's size and what it replaces; **Import** fills that tab, **Cancel** leaves it. A body cut off in the trace shows a warning in the dialog and, after the import, in the red bar. With the setting off, the tab shows the browser's error page. |
| 2.11h | Without a tenant, the same path: XSLT tab → **Import…** → paste `http://localhost/#share/XYwxC8IwEIX_SrjZUlwlBhwcnBTbzTgc9sBikpZLIpXS_-6lm273vve-m2HyDnYK9Jk7YtV3ewtttbVg9CmRV_GVhRws1EbX68bARsEUXSpauZ-EgqPE2wwBPZWioSCw-UR5UkZvdHktjtcLLHchIw8jcerpz2wZH_SjeAwZXZUoJlGXLw` (a link in xsltDebugX's format with a body, one header and one property, no mapping) | The input body is `<Order id="T-1">…`, header `SenderSystem` = `ERP`, property `Trace` = `manual-test`; the mapping stays as it was. On macOS before 13.3 an error says the web view cannot unpack the link. |
| 2.11i | `scripts/mapping-functions.groovy`, **Live** off: **Function** `getHeader`, run; then **Other name…**, type `setProperty`, fill both arguments, run | The result is the second argument, and the arguments stay filled after the run. |
| 2.11k | Receiver on, script/mapping already run once, **Live** on; import only body/headers/properties | No automatic run, including a previously scheduled Live callback. The status asks for Ctrl+Enter. **Run** executes the existing code on the new message. |
| 2.11l | Send another receiver link while one is being unpacked or confirmed; quickly toggle/change the receiver port | Only the newest undecided message is offered, with a replacement note. Settings settle on the latest choice; an occupied port reports an error, without silently choosing another. |
| 2.11m | Import valid XML containing `<parsererror>`, then an XHTML `<h:parsererror>`; import cut-off XML and invalid JSON | Valid documents have no warning; malformed bodies warn but remain importable. Check Edge and Firefox in browser mode, and WKWebView on macOS when available. |
| 2.11d | New script in 1.x with `log.logInfo(TimeZone.default.ID + ' ' + org.apache.commons.lang3.StringUtils.reverse('abc'))` in `processData`; then change `logInfo` to `info` | **Console** shows `[log.logInfo] Etc/UTC cba` with a note; with `info` the run fails with `MissingMethodException … ScriptLogger.info()`, as on a tenant. |

### XSD and XPath

| # | Step | Expected |
|---|---|---|
| 2.12 | XSD: `schemas/salesorder.xsd` against the output of 2.2 | Valid. Break an element → an error with its line. |
| 2.13 | XPath on `inputs/order.xml`, once 3.1, once 1.0 | A result appears. |
| 2.13a | XPath `//Item/@sku`; click a result's ↗; cursor on a `qty` attribute → **XPath at cursor** → *This node*; then ↑ in the field | Both `sku` attributes are marked; ↗ selects one; the field gets `/Order/Items/Item[2]/@qty` (also in the clipboard) and runs; ↑ brings `//Item/@sku` back. |
| 2.14 | Press Ctrl+Enter several times quickly, or change the input while it runs | The result always belongs to the current input, never to an older run. |

### Flow Steps

| # | Step | Expected |
|---|---|---|
| 2.14a | Start the app without a project | Input, mapping, script, XPath and every flow step are empty; the empty editors point to *Examples…*; nothing runs. |
| 2.15 | **Content Modifier** on `inputs/order.xml`: a header row `OrderId`, XPath `/Order/@number` | The value of each row and the output message. |
| 2.16 | **Filter** `/Order/Items/Item[@qty > 1]`; change the Value Type | The filtered body; the result follows the Value Type. |
| 2.17 | **Router**: *+ Route* twice, one Non-XML condition `${header.SenderId} = 'ERP'` with that header set, the other the default; change the condition | The route the message takes; another route after the change. |
| 2.18 | **Splitter** `/Order/Items/Item`, then *Gather* on its result | The split messages; Combine gives SAP's multi-mapping format. |
| 2.19 | Splitter on `inputs/orders.xml`, XPath `/src:Orders/src:Order`, namespace `src` = `urn:demo:integration:orders:source:v1` | One message per order. |
| 2.20 | *Use as input* on a result | The result becomes the next step's input. |
| 2.21 | Switch between steps and tabs | Each step keeps its settings and last result. |
| 2.22a | **Converter**: XML to JSON on `inputs/order.xml`, *Use as input*, then JSON to XML | JSON with `@` attributes, then XML like the input (without indentation). |
| 2.22b | **Converter**: XML to CSV on `inputs/order.xml`, path `/Order/Items/Item`, *Include Attribute Values* | A header line and one line per item. |
| 2.22c | **Message Digest** on `inputs/order.xml` with the defaults; then change the body's attribute order or indentation inside a tag and run again; then *None* | The header `SAPMessageDigest` with a Base64 value and the canonical form; the same digest after the change; with *None* another digest and a note that this is an assumption. |
| 2.22d | **Encoder**: *Base64 Encode* on `inputs/order.xml`; then *GZIP Compress*, *Use as input*, **Decoder** *GZIP Decompress*; then *ZIP Compress* with a header `CamelFileName` = `in/order.xml`, *Save…* as `inputs/order.zip`, click that file in the tree and run **Decoder** *ZIP Decompress*; then *MIME Multipart Encode* | Base64 in lines of 76 characters with an info that the details are Camel's; GZIP shows "binary", its size and a hex dump starting `1f 8b 08`, the input pane shows a binary body, and the Decoder gives back the order; ZIP sets `CamelFileName` = `order.xml.zip`, the saved file opens as a binary input, and ZIP Decompress gives the order with `CamelFileName` = `order.xml`; MIME Multipart fails with `UnsupportedInSimulator`. |
| 2.23a | Click the **Fidelity** button next to the runtime in the XSLT and Groovy tabs and next to *Run step* for the Encoder and the Gather | It names the weakest basis (*tenant-verified*, *documented* or *assumptions*); the dialog lists the rows of `docs/fidelity.md` for that step with their basis. |
| 2.23 | Start a step and change the body or its settings at once | The result belongs to the state at the start; the change applies to the next run. |

### Editor and files

| # | Step | Expected |
|---|---|---|
| 2.24 | Change a file → **Save**, **Save as…** | Saved; an existing name asks before it is overwritten. |
| 2.25 | Right-click in the tree: rename, copy, delete | Works. |
| 2.26 | **✕**, then open another project | No error; the old files are gone. |
| 2.26a | Change a mapping, then **×** next to its name; *Cancel*; again **×** and confirm | The first time the change stays; then the editor is empty and the file on disk is unchanged. **Save** in the empty editor asks for a path. |

## 3. iFlow import (required)

| # | Step | Expected |
|---|---|---|
| 3.0 | Close the project; on the start screen **Import iFlow…** with the ZIP from section 0, then a new folder name; repeat with the same name | A new project opens with `mappings/`, `platform/`, `cpi-workbench.json` and the iFlow under `iflows/`; its steps are listed. The second time the folder is refused (*exists already*) and no project opens. |
| 3.1 | **Import iFlow** with the ZIP from section 0 | Unpacked into `iflows/`; lists the Groovy, XSLT, XML Validator, Content Modifier, Filter, Router, Splitter, Gather, Message Mapping (**M**) and converter (**T**) steps. |
| 3.1b | **Import iFlow** with an exported Value Mapping, then with an exported integration package that has an iFlow using a script collection | The status names the value mapping rows; *Platform* → *Value Mappings* shows both directions. The package's iFlows and collections are under `iflows/`, other types are named as skipped, and the collection's script opens from the iFlow's Groovy step. |
| 3.1c | After 3.1: the list under **iFlows**, then click *Write to data store*; in **Diagram**, double-click *Enrich order* | The list shows the HTTPS and SOAP adapters, the Router's routes *Other (default)* and *ERP* with its condition, the exception subprocess and the local process *Archive order*. *Write to data store* opens the Diagram tab with its settings (`storageName`); the double-click opens `enrich.groovy`. |
| 3.1d | After 3.1: **Where used**; click `https://s4.example.com/orders`; click *S4*; back in **Where used**, **← Overview** | The overview lists the addresses and (folded) files, and under *Parameters* `GroupSize`, `LogLevel` and `RootName` with values and the steps that use them. The click searches the address as *Address*: one hit, the SOAP receiver's `address` (*SOAP receiver from …*) with the match highlighted; *S4* selects it in the Diagram, whose details show the SOAP adapter's settings. **← Overview** shows the overview again; a search for `Orders` lists the parameter `RootName` (its value). |
| 3.1e | Optional: import the ZIP from section 0 a second time under another folder name | The Diagram's iFlow picker, the iFlow list and Where used show both as *Order_Replication · <folder>*. |
| 3.2 | Click a Groovy or XSLT step | The file opens with the step's function name and script version. |
| 3.3 | Click the Content Modifier, Filter, Gather and *JSON to XML* | They open in Flow Steps with their settings; `{{parameters}}` come from `parameters.prop` (the converter's root element `Orders`). The **×** after *From …* gives the step its empty starting settings back; the other steps keep theirs. |
| 3.3a | Click *Map order* (**M**) | Its script `lookup.gsh` opens with the mapping function `getHeader`. |
| 3.4 | Optional: an iFlow exported from your own tenant, without secrets | Imports without errors; steps run or report `UnsupportedInSimulator`. |

## 4. Special cases (optional)

* **User name outside the Windows code page** (for example Polish letters on a German Windows), if you
  have such an account: Groovy still starts, through `C:\ProgramData\CPI Workbench <SID>`
  ([Installing](install.md#if-the-groovy-engines-do-not-start)).
* **Idle stop:** the Groovy engines stop after 10 idle minutes. Running a script afterwards starts
  them again, a little slower.

## 5. MCP server with Claude Code (required)

Use a copy of `examples/demo-project`. Setup details: [MCP server](mcp.md).

| # | Step | Expected |
|---|---|---|
| 5.1 | Create `.mcp.json` in the project folder (below) | – |
| 5.2 | Start `claude` in the project folder (terminal or the Code tab of the desktop app) | Claude Code asks once before it starts the project's server → allow. |
| 5.3 | `/mcp` | `cpi-workbench` is connected with 12 tools: `project_info`, `open_project`, `read_file`, `run_xslt`, `run_groovy`, `validate_xsd`, `evaluate_xpath`, `run_flow_step`, `save_file`, `import_iflow`, `iflow_where_used`, `reference`. |
| 5.4 | *"Use project_info to show what is in the project."* | Files and installed runtimes. |
| 5.5 | *"Run mappings/order-to-salesorder.xsl on inputs/order.xml with header SenderSystem=ERP and property CustomerGroup=02, then on both Saxon versions."* | Same result as 2.2 and 2.3. |
| 5.6 | *"Run scripts/enrich-order.groovy on inputs/order.xml and show the message log."* | Same result as 2.6; the first run is slower while the engine starts. |
| 5.7 | *"Validate the mapping's output against schemas/salesorder.xsd."* | Valid. |
| 5.8 | *"Split inputs/orders.xml per Order (namespace src = urn:demo:integration:orders:source:v1) and combine the result with Gather."* | Two `run_flow_step` calls and the combined message; the Gather takes the Splitter's messages by its `runId` (`messagesFrom`), not as copied text. |
| 5.9 | *"Read C:\Windows\win.ini as bodyPath with evaluate_xpath."* | Refused with an error: the path is outside the project. |
| 5.10 | Keep the app open and run something there meanwhile | Both work; the agent's runs do not appear in the app (by design). |
| 5.11 | Claude Desktop with `--project` on the copy ([MCP server](mcp.md)), without a filesystem server: *"Look at inputs/order.xml and write an XSLT that maps it to the format of schemas/salesorder.xsd; try it and validate the result."* | The agent reads both files with `read_file`, runs `run_xslt` and `validate_xsd` until the output is valid, and shows the mapping; nothing is saved. |
| 5.12 | *"Save it as mappings/agent-order.xsl."*, then the same again | The client may ask before the writing tool; the file appears in the app after **↻**. The second time the agent is told the file exists and asks whether to replace it. |
| 5.13 | *"Import the iFlow from <path of an exported .zip> and tell me what its Content Modifier does with a sample message."* | `import_iflow`, then `run_flow_step` on its step; the app shows the iFlow after **↻**. |
| 5.14 | **+** → **develop_step** with a task | The agent follows the steps of the prompt (project, input, write, run, report). |
| 5.15 | Claude Desktop with `--workspace` on a folder holding the copy and one other project: *"Which projects are there? Open the copy, then create a new project Billing."* | `project_info` lists both under `projects`; `open_project` opens the copy, then creates `Billing` with `platform/` and `cpi-workbench.json`; nothing outside the workspace is touched. |

`.mcp.json` for the `setup.exe` install (for the `.msi`: `C:\\Program Files\\CPI Workbench\\cpi-workbench.exe`):

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

If 5.3 does not show it connected, note the error from `/mcp`, then run the smoke test without a
client (Node 22.18+, from the checkout):

```powershell
node app\scripts\mcp-smoke.mjs "$env:LOCALAPPDATA\CPI Workbench\cpi-workbench.exe" testdata\regression-project
$LASTEXITCODE   # expected: 0
```

## 6. Other MCP clients (optional)

Claude Desktop and Codex need `--project` or `--workspace`; the configurations are in [MCP server](mcp.md#setup).
Restart the client, then repeat 5.4 and 5.5.

## 7. Release

When the required sections pass, follow [Releasing](building.md#releasing). After the release job,
check that the release holds the installers, the engine jars and `SHA256SUMS.txt`.

After uninstalling (*Settings → Apps*), `C:\ProgramData\CPI Workbench <SID>` may remain (section 4);
it can be deleted.
