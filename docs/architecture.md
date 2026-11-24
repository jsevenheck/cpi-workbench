# Architecture

## Repository layout

| Path | What |
|---|---|
| `engine/core` | Shared Java code (built for Java 17, run on 21): JSON, JSON-RPC server (stdio and HTTP), project files and path checks, iFlow import, test runner. |
| `engine/xml`, `engine/xml-he99`, `engine/xml-he12` | CPI XSLT step, XSD validation, XPath, the XPath-based flow steps (Filter, Splitter, Gather), the converters, the Encoder / Decoder and the Message Digest (XML canonicalization of the JDK). The same sources are built against Saxon-HE 9.9.1-8 and 12.9. |
| `engine/native` | GraalVM native-image build (`build-native.sh`), reflection metadata generator, workload smoke test. |
| `engine/groovy`, `engine/groovy-v1`, `engine/groovy-v2` | Groovy engines: shared runner, clean-room `com.sap.*` API (`src/api-common` with the Script API v1, `src/api-v2`), and the two builds with their entry points (Groovy 2.4.21 and Groovy 4.0.29, both on Camel 3.14.7 like a tenant). `build-jre.sh` trims the Java 21 runtime. |
| `app/` | Svelte 5 + Monaco UI (`src/`), Tauri 2 shell (`src-tauri/`, with the MCP server in `src/mcp/`), browser e2e tests (`e2e/`). |
| `examples/demo-project/` | Sample project for the app: mappings, scripts, a schema, inputs and platform data. |
| `testdata/regression-project/` | Project with test cases (`case.json`) that CI, the browser e2e and the shell tests run. |
| `probes/` | Fingerprint and behaviour probes for a real tenant, the simulator's baseline output and a tenant's results. |

## Engines and protocol

The desktop shell starts each engine as a sidecar process on demand and talks JSON-RPC to it
over stdio. Every engine can also serve HTTP on localhost (`serve --http <port>`) for the
browser mode. The main methods are:

* `xslt.run` (explicit run), `xslt.preview` (automatic runs, separate configuration and cache without
  reflexive Java functions), `xsd.validate`, `xpath.eval`, `split.run`, `filter.run`, `gather.run`, `convert.run`,
  `encode.run`, `decode.run`, `digest.run` (XML engines),
* `groovy.run`, `simple.eval`, `contentModifier.run` (Groovy engines); the Content Modifier keeps one
  exchange across XPath using single-use, project-bound continuations (`contentModifier.advance` and
  `contentModifier.discard`, up to eight paused exchanges per engine, expiring after one minute).
  The UI evaluates a Router route by route with `xpath.eval` and `simple.eval`,
* `tests.run`, and `project.*`, `file.*`, `iflow.*` for files and imported models (all engines);
  `iflow.index` / `iflow.search` (`IflowIndex`) back the Where used tab and the MCP tool `iflow_where_used`.

Project file access, Init, iFlow discovery and project-relative XML resolution check real paths
(`ProjectPaths`, `ProjectFiles`, `ProjectResolver`). A link selected as the project root defines the
boundary at its target; links below it must stay inside. Discovery checks directories before listing
and files before parsing. These checks are not a sandbox or a guarantee against another process
replacing directories concurrently. Over HTTP, the engines answer pages from `localhost`, `127.0.0.1`
and the desktop shell only: requests
need an `X-Cpiwb` header, which forces a CORS preflight, and the origin's host is compared exactly.
A request whose `Host` header does not name this machine, or whose `Origin` is not local, gets 403:
after DNS rebinding a foreign page is same-origin for the browser and no preflight happens, but its
requests still carry the foreign host name. The browser UI uses `/project/rpc`: every request carries
`projectRoot` (the absolute directory returned by `project.open`, or `null` without a project; with `create`,
`project.open` first makes the folder and refuses one that exists). Each call
gets its own project context, so one tab's Open or Close does not alter another's. An unavailable root
fails rather than falling back. The desktop shell and the MCP server wrap the same scoped request in `project.request`
when sending it over stdio; plain stdio requests and the legacy local `/rpc`
endpoint keep their stateful contract. Both HTTP endpoints admit up to four RPCs per engine, with a
32 MiB request-body cap (HTTP 413) and a roughly ten-second request read deadline. The JDK HTTP
server uses a 24-connection cap, a 16 KiB header cap and a bounded executor. Excess transport
connections and stalled uploads can be closed before an HTTP reply. A parsed request waits for the engine
up to the execution timeout plus five seconds (35 s by default), so it outlives the call ahead; a full admission pool or expired queue wait returns HTTP 503 without
running that request. Execution has a separate timeout. `serve --http` runs in a child process
(`--worker`) and restarts it only when an executing call times out. After worker replacement both UIs
still send the selected project on the first request. Failed calls are not replayed.

Over stdio, the Rust supervisor allows 15 seconds for startup (until the engine is ready) and
32 seconds to send and receive a request; a call waits up to 50 seconds for its profile slot, longer
than the call ahead of it can take, so a file read or save during a long run waits for it. Java's own
default execution timeout is 30 seconds. Stdio frames are limited to 192 MiB: a larger request is
refused before it is sent (`UnsupportedInSimulator`) and leaves the worker running; a larger response
stops that worker, whose pipe is then out of step. A queue timeout does not touch
the busy worker; an expired startup or exchange stops and reaps that worker. Shutdown stops new
submissions and interrupts waits for the direct child, not arbitrary processes started by user code.

* **XML engines** are GraalVM native executables, so XSLT needs no JVM at runtime. The same code
  also runs as a jar (`java -jar engine/xml-he99/target/cpiwb-xml-he99.jar`).
* **Groovy engines** run on a trimmed Java 21 runtime with an AppCDS archive, in UTC with the locale `en_US`
  like a tenant (the XML engines too). Groovy 2 and 4 run in
  separate processes, so they never mix and a runaway script only stops its own engine. The
  `Message` object is backed by a real Camel exchange, so type conversion and header handling come
  from Camel itself. On Windows, Java cannot start from a folder whose path has characters outside
  the ANSI code page ([JDK-8205991](https://bugs.openjdk.org/browse/JDK-8205991)). If the install
  folder has non-ASCII characters, the shell reaches it through a directory junction in
  `%ProgramData%\CPI Workbench <user SID>`, a folder only that user, SYSTEM and Administrators can
  write (`app/src-tauri/src/ascii_alias.rs`); the AppCDS archives go there too.
* **Caches**: compiled stylesheets and scripts are cached by content hash and profile, so changing
  only the input skips compilation.

The shell's executable also runs headless as an MCP server (`cpi-workbench mcp`, [MCP server](mcp.md)):
it starts the same engines through the same supervisor (`engine.rs`), sends the open project with each call
(`project.request`, so `open_project` in a `--workspace` switches without restarting an engine), and maps each
tool to engine calls. Its flow-step tool repeats the UI's calls to the engines, step by step (`mcp/flow.rs`);
`mcp/whereused.rs`, `mcp/functions.rs` and `mcp/iflowmodel.rs` repeat what the Where used tab, the Groovy tab's
function list and the Diagram tab compute in TypeScript. Fixtures under `testdata/*-parity` keep each pair in step.
The app leads: the tools add no function of their own.

## CPI Helper receiver

The desktop receiver is separate from the engines' HTTP servers and is **off by default**.
`app/src-tauri/src/receiver.rs` binds `127.0.0.1` at the configured port (default 7790). Its page
reads `#share/<data>` and POSTs the token to `/message` on the same origin. Host/Origin checks,
16 KiB request headers, an 8 MiB POST limit, at most four active handlers and a 15-second read
deadline bound HTTP input. Stopping prevents old handlers from delivering more messages.

The shell emits the token to `app/src/lib/receiver.svelte.ts`. Settings changes are serialized;
message generations keep the newest undecided link. `share.ts` limits unpacked links to
128 million UTF-16 code units and checks XML/JSON bodies up to 64 million. The UI confirms the
import, reuses the parsed message and disarms automatic Groovy/XSLT runs, including pending
Live callbacks. This is not sender authentication: any browser page can open a receiver link.
The receiver opens no engine RPC endpoint and is not started by MCP.

The design reasons and the build-vs-reuse principle are in the [plan](plan.md) §3 and §5.

## Measured performance

Measured on a Linux x86-64 build machine for 0.2.0 (Java 17; Groovy 1.x then on Camel 2.24) and not
measured again since; Windows and macOS have not been measured.

| | XSLT (native) | Groovy 1.x (JVM) | Groovy 2.x (JVM) |
|---|---|---|---|
| Engine start to ready | ≈ 10 ms | ≈ 220 ms (with AppCDS, 440 ms without) | ≈ 230 ms (410 ms without) |
| First run | ≈ 10 ms | ≈ 170 ms | ≈ 420 ms |
| Later runs (cached) | < 1 ms | ≈ 2 ms | ≈ 2 ms |
| Peak memory | ≈ 60 MB (270 KB input) | ≈ 75 MB | ≈ 150 MB |

A separate 0.3.0 size observation recorded 48 / 54 MB native XML executables (about 16 / 18 MB
compressed), 15 + 16 MB Groovy jars and an 81 MB Java 21 runtime (about 51 MB compressed).
These are historical artifact sizes, not guarantees for current bundles.

A 7.4 MB message took 1.3 s through XSLT and 0.45 s through Groovy over HTTP.
