# Manual test before a release

CI covers the engine tests, the browser e2e, the installer builds and the release executable as an
MCP server (`app/scripts/mcp-smoke.mjs`). It does not cover installing the app, using the real
desktop window or a real MCP client. This page is the hand check for that, written for Windows; on
macOS the steps are the same apart from section 1 and the paths.

Sections 1–3 and 5 are required, 4 and 6 optional; about 1 to 1.5 hours. For each failure, note the
step number, what you expected, what happened and the error text or a screenshot. Do not release
while a required step fails.

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

### XSLT

| # | Step | Expected |
|---|---|---|
| 2.1 | `mappings/orders-to-batch.xsl`, input `inputs/orders.xml` | Output appears (live or Ctrl+Enter) and matches `inputs/order-batch.xml`. |
| 2.2 | `mappings/order-to-salesorder.xsl`, input `inputs/order.xml`, header `SenderSystem`, property `CustomerGroup` | The output uses both values; **Parameters** shows them bound from header and property. |
| 2.3 | **Compare** | Runs on Saxon-HE 9.9 and 12 and lists the differences, or none. |
| 2.4 | Type an error into the mapping | The error is marked at its line; nothing crashes. |
| 2.5 | **Format** | Re-indents; the output stays the same. |

### Groovy

The first run starts the Java engines and can take a few seconds.

| # | Step | Expected |
|---|---|---|
| 2.6 | `scripts/enrich-order.groovy` (1.x), input `inputs/order.xml` | Runs. **MPL**, **Console** and **API calls** show entries (Value Mapping, Secure Store from `platform/`). |
| 2.7 | `scripts/validate-order-batch.groovy` (2.x), input `inputs/order-batch.xml` | Runs and sets headers and properties. |
| 2.8 | `scripts/validate-order-batch-json.groovy`, input `inputs/order-batch.json` | Runs with the JSON body. |
| 2.9 | `scripts/route-by-partner.groovy`, input `inputs/order.xml`, header `SalesChannel` = `web-eu` | Reads the Partner Directory. |
| 2.10 | **Compare** on a script | Runs on 1.x and 2.x. |
| 2.11 | Type `message.` in a script; also `messageLogFactory.` and Ctrl+Space | The Script API methods are listed. |
| 2.11a | `scripts/mapping-functions.groovy`: **Run as** shows its functions; click `countPerContext`, Argument 1 `["A", "B"]` and `["C"]` on two lines | The label next to **fn** says *Mapping function*; **Arguments** replaces the body; **Result** shows `["2"]` and `["1"]`. |
| 2.11b | **Run as** `getHeader`, Argument 1 `SenderSystem` with that header set; then **null** | **Result** shows the header's value; with **null**, `null`. Opening another script brings the **Body** tab back. |
| 2.11c | **⚙** (bottom left) → **Check for updates** | The installed version is shown. Before the release: "This is the latest version (latest release: 0.2.0)"; with a newer release, **Open release page** opens it in the browser. If GitHub refuses (e.g. 403), the reason is shown and **Open releases page** opens the list. |
| 2.11d | New script in 1.x with `log.logInfo(TimeZone.default.ID + ' ' + org.apache.commons.lang3.StringUtils.reverse('abc'))` in `processData`; then change `logInfo` to `info` | **Console** shows `[log.logInfo] Etc/UTC cba` with a note; with `info` the run fails with `MissingMethodException … ScriptLogger.info()`, as on a tenant. |

### XSD and XPath

| # | Step | Expected |
|---|---|---|
| 2.12 | XSD: `schemas/salesorder.xsd` against the output of 2.2 | Valid. Break an element → an error with its line. |
| 2.13 | XPath on `inputs/order.xml`, once 3.1, once 1.0 | A result appears. |
| 2.14 | Press Ctrl+Enter several times quickly, or change the input while it runs | The result always belongs to the current input, never to an older run. |

### Flow Steps

| # | Step | Expected |
|---|---|---|
| 2.15 | **Content Modifier** with the sample settings | The value of each row and the output message. |
| 2.16 | **Filter** (`/Order/Item[@qty > 1]`); change the Value Type | The filtered body; the result follows the Value Type. |
| 2.17 | **Router**; change a condition | The route the message takes; another route after the change. |
| 2.18 | **Splitter**, then *Gather* on its result | The split messages; Combine gives SAP's multi-mapping format. |
| 2.19 | Splitter on `inputs/orders.xml`, XPath `/src:Orders/src:Order`, namespace `src` = `urn:demo:integration:orders:source:v1` | One message per order. |
| 2.20 | *Use as input* on a result | The result becomes the next step's input. |
| 2.21 | Switch between steps and tabs | Each step keeps its settings and last result. |
| 2.22a | **Converter**: XML to JSON on `inputs/order.xml`, *Use as input*, then JSON to XML | JSON with `@` attributes, then XML like the input (without indentation). |
| 2.22b | **Converter**: XML to CSV on `inputs/order.xml`, path `/Order/Items/Item`, *Include Attribute Values* | A header line and one line per item. |
| 2.23 | Start a step and change the body or its settings at once | The result belongs to the state at the start; the change applies to the next run. |

### Editor and files

| # | Step | Expected |
|---|---|---|
| 2.24 | Change a file → **Save**, **Save as…** | Saved; an existing name asks before it is overwritten. |
| 2.25 | Right-click in the tree: rename, copy, delete | Works. |
| 2.26 | **✕**, then open another project | No error; the old files are gone. |

## 3. iFlow import (required)

| # | Step | Expected |
|---|---|---|
| 3.1 | **Import iFlow** with the ZIP from section 0 | Unpacked into `iflows/`; lists the Groovy, XSLT, XML Validator, Content Modifier, Filter, Router, Splitter, Gather, Message Mapping (**M**) and converter (**T**) steps. |
| 3.2 | Click a Groovy or XSLT step | The file opens with the step's function name and script version. |
| 3.3 | Click the Content Modifier, Filter, Gather and *JSON to XML* | They open in Flow Steps with their settings; `{{parameters}}` come from `parameters.prop` (the converter's root element `Orders`). |
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
| 5.3 | `/mcp` | `cpi-workbench` is connected with 6 tools: `project_info`, `run_xslt`, `run_groovy`, `validate_xsd`, `evaluate_xpath`, `run_flow_step`. |
| 5.4 | *"Use project_info to show what is in the project."* | Files and installed runtimes. |
| 5.5 | *"Run mappings/order-to-salesorder.xsl on inputs/order.xml with header SenderSystem=ERP and property CustomerGroup=02, then on both Saxon versions."* | Same result as 2.2 and 2.3. |
| 5.6 | *"Run scripts/enrich-order.groovy on inputs/order.xml and show the message log."* | Same result as 2.6; the first run is slower while the engine starts. |
| 5.7 | *"Validate the mapping's output against schemas/salesorder.xsd."* | Valid. |
| 5.8 | *"Split inputs/orders.xml per Order (namespace src = urn:demo:integration:orders:source:v1) and combine the result with Gather."* | Two `run_flow_step` calls and the combined message. |
| 5.9 | *"Read C:\Windows\win.ini as bodyPath with evaluate_xpath."* | Refused with an error: the path is outside the project. |
| 5.10 | Keep the app open and run something there meanwhile | Both work; the agent's runs do not appear in the app (by design). |

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

Claude Desktop and Codex need `--project`; the configurations are in [MCP server](mcp.md#setup).
Restart the client, then repeat 5.4 and 5.5.

## 7. Release

When the required sections pass, follow [Releasing](building.md#releasing). After the release job,
check that the release holds the installers, the engine jars and `SHA256SUMS.txt`.

After uninstalling (*Settings → Apps*), `C:\ProgramData\CPI Workbench <SID>` may remain (section 4);
it can be deleted.
