//! `cpi-workbench mcp`: a Model Context Protocol server over stdio, so that a coding agent can run
//! mappings, scripts and flow steps on the simulator while it writes them.
//!
//! * Transport: stdio, one JSON-RPC 2.0 message per line (MCP "stdio" transport). Logs go to stderr.
//! * Protocol: dual-era as the 2026-07-28 revision describes it ("Versioning: Backward Compatibility").
//!   A request that carries `_meta["io.modelcontextprotocol/protocolVersion"]` is served statelessly
//!   under 2026-07-28 (`server/discover`, `resultType`, caching hints); a client that opens with
//!   `initialize` gets the negotiated legacy revision (2025-11-25 back to 2024-11-05).
//! * Features: tools, one per tab of the app (see `tools.rs`), plus reading and saving project files,
//!   importing an iFlow, results by runId (`runs.rs`) and a reference generated from the sources
//!   (`files.rs`, `reference.rs`), so that an
//!   agent without file access of its own (Claude Desktop) can work in the project; one prompt. No
//!   resources, logging, sampling or roots.
//! * Scope: one project folder (`--project`; default `CLAUDE_PROJECT_DIR`, else the working directory).
//!   Files are read through the engines, which refuse paths outside the project. Groovy scripts run without a sandbox, as in
//!   the app; docs/mcp.md says so.

mod files;
mod flow;
mod reference;
mod runs;
mod tools;

use crate::engine::{self, Engines};
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::io::{BufRead, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

/// The stateless revision this server implements.
pub const MODERN: &str = "2026-07-28";
/// Revisions that open with `initialize`, newest first.
pub const LEGACY: &[&str] = &["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];

const META_VERSION: &str = "io.modelcontextprotocol/protocolVersion";
const META_SERVER: &str = "io.modelcontextprotocol/serverInfo";
const META_CAPABILITIES: &str = "io.modelcontextprotocol/clientCapabilities";

const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;
const UNSUPPORTED_PROTOCOL_VERSION: i64 = -32022;

/// Tool lists change only with a new release; clients may keep them for an hour.
const LIST_TTL_MS: u64 = 3_600_000;

const INSTRUCTIONS: &str = "CPI Workbench simulates SAP Integration Suite (Cloud Integration) steps locally: \
XSLT mappings (Saxon-HE 9.9 like CPI, or 12), Groovy scripts (Script API 1.x on Groovy 2.4, \
2.x on Groovy 4, both on Camel 3.14) and Message Mapping functions, XSD validation, XPath and the flow steps Content \
Modifier, Filter, Router, Splitter, Gather and Converter, as the tabs of the CPI Workbench app do.\n\
How to work: call project_info first (files, iFlow steps with their configuration, runtimes); read the input \
message, the iFlow's scripts and mappings with read_file; write the step and run it with run_xslt, run_groovy, \
validate_xsd, evaluate_xpath or run_flow_step, passing the source inline (mapping, script, schema, step) until the \
result is right. A run that produces a message returns a runId: pass it as messageFrom to the next step instead \
of copying the body (body, headers and properties flow on as in an iFlow; a Splitter's messages are runId/1, \
runId/2, …; a Gather takes messagesFrom). Long bodies come back shortened; read_file with runId shows them whole, \
save_file with contentFrom keeps one. Run on both runtimes (runtime: both) when the version matters. Show the user the result and, for flow steps, the configuration as they would enter \
it in the iFlow. Save files with save_file only when the user wants them; the app shows them after a refresh. \
reference explains the Script API, the platform/ data formats and what is verified against CPI.\n\
Paths are relative to the project folder; the tools read and write inside it only. Groovy scripts, and reflexive \
java: calls and fn:transform() in XSLT, run without a sandbox, with the user's rights. The simulator is not a \
tenant: messages starting with 'UnsupportedInSimulator' name behaviour it does not simulate.";

/// The prompts: a starting point the user picks in the client.
fn prompt_definitions() -> Value {
    json!([{
        "name": "develop_step",
        "title": "Develop a step on the simulator",
        "description": "Build an XSLT mapping, Groovy script, XSD, XPath or flow step for a task and try it on the simulator.",
        "arguments": [
            { "name": "task", "description": "What the step should do, e.g. map the order to the S/4 format.", "required": true },
            { "name": "iflow", "description": "The iFlow it belongs to (a folder under iflows/ or a step name).", "required": false },
            { "name": "input", "description": "Project file with a sample input message.", "required": false },
        ],
    }])
}

fn get_prompt(params: &Value) -> Result<Value, (i64, String)> {
    let name = params.get("name").and_then(Value::as_str).ok_or((INVALID_PARAMS, "prompts/get needs a prompt name".to_string()))?;
    if name != "develop_step" {
        return Err((INVALID_PARAMS, format!("Unknown prompt: {name}")));
    }
    let arg = |k: &str| params.get("arguments").and_then(|a| a.get(k)).and_then(Value::as_str).filter(|s| !s.trim().is_empty());
    let task = arg("task").ok_or((INVALID_PARAMS, "Missing argument: task".to_string()))?;
    let mut text = format!("Task: {task}\n");
    if let Some(f) = arg("iflow") {
        text.push_str(&format!("iFlow: {f}\n"));
    }
    if let Some(i) = arg("input") {
        text.push_str(&format!("Sample input: {i}\n"));
    }
    text.push_str("\nUse the CPI Workbench tools:\n\
1. project_info: the project's files, the iFlow's steps and the installed runtimes.\n\
2. read_file: the sample input and the files of the steps around the one to build.\n\
3. Pick the step type CPI would use and write it. For scripts or mapping functions, check reference script-api.\n\
4. Run it on the simulator with the sample input and fix it until the output is right; try an edge case too \
(empty or missing fields, several records). Pass a result to the next step with messageFrom and its runId.\n\
5. Report the final source or configuration (as entered in the iFlow), the output it gave, and anything the \
simulator does not cover (fidelity).\n\
6. Ask before saving; then save_file into the project's folder for its kind.\n");
    Ok(json!({ "description": "Develop a step on the simulator", "messages": [{ "role": "user", "content": { "type": "text", "text": text } }] }))
}

pub struct Server {
    engines: Arc<Engines>,
    project: Option<PathBuf>,
    runs: runs::Runs,
    /// Held from the check whether a file exists to the write: requests run in parallel (one thread each), and
    /// without it two calls without `overwrite` could both find a path free and both write it.
    writes: Mutex<()>,
}

/// Entry point for `cpi-workbench mcp [--project <folder>]`. Returns the process exit code.
pub fn main(args: &[String], resource_dir: Option<PathBuf>, cache_dir: Option<PathBuf>) -> i32 {
    let project = match parse_args(args) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("cpi-workbench mcp: {e}\nUsage: cpi-workbench mcp [--project <folder>]");
            return 2;
        }
    };
    let engines = Arc::new(Engines::default());
    *engines.resource_dir.lock().unwrap() = resource_dir;
    *engines.cache_dir.lock().unwrap() = cache_dir;
    if let Some(dir) = &project {
        *engines.on_start.lock().unwrap() =
            Some(("project.open".into(), json!({ "dir": dir.to_string_lossy() })));
    }
    eprintln!("cpi-workbench mcp: project {}; runtimes {:?}",
        project.as_deref().map(|p| p.display().to_string()).unwrap_or_else(|| "(none)".into()), engines.available());
    let idle = engines.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(60));
        idle.stop_idle(engine::IDLE_LIMIT);
    });
    let server = Arc::new(Server::new(engines.clone(), project));
    // Requests still running when stdin closes are dropped: the client no longer reads their answers.
    drop(serve(server, std::io::stdin().lock(), Arc::new(Mutex::new(std::io::stdout()))));
    0
}

fn parse_args(args: &[String]) -> Result<Option<PathBuf>, String> {
    let mut project = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--project" => project = Some(it.next().ok_or("--project needs a folder")?.clone()),
            other => return Err(format!("unknown argument '{other}'")),
        }
    }
    // Default: the project Claude Code runs in (it sets CLAUDE_PROJECT_DIR for the servers it starts),
    // else the working directory.
    let dir = match project.or_else(|| std::env::var("CLAUDE_PROJECT_DIR").ok().filter(|d| !d.is_empty())) {
        Some(p) => PathBuf::from(p),
        None => std::env::current_dir().map_err(|e| e.to_string())?,
    };
    let dir = std::fs::canonicalize(&dir).map_err(|e| format!("project folder {}: {e}", dir.display()))?;
    if !dir.is_dir() {
        return Err(format!("{} is not a folder", dir.display()));
    }
    Ok(Some(engine::plain_path(dir)))
}

/// Reads requests until stdin closes. Each request runs on its own thread, so a long Groovy run does
/// not hold up `tools/list`; the engines serialise calls per runtime. Returns the requests still running.
pub fn serve<R: BufRead, W: Write + Send + 'static>(server: Arc<Server>, input: R, out: Arc<Mutex<W>>) -> Vec<JoinHandle<()>> {
    // Requests in flight, by id, and whether the client cancelled them.
    let in_flight: Arc<Mutex<HashMap<String, bool>>> = Arc::default();
    let mut workers = Vec::new();
    for line in input.lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        let msg: Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(e) => {
                send(&out, error(Value::Null, PARSE_ERROR, &format!("Parse error: {e}"), None));
                continue;
            }
        };
        let Some(obj) = msg.as_object() else {
            send(&out, error(Value::Null, INVALID_REQUEST, "Invalid Request: expected one JSON-RPC object per line", None));
            continue;
        };
        let Some(method) = obj.get("method").and_then(Value::as_str).map(str::to_string) else {
            // A response (this server sends no requests, so it is ignored), or no valid message at all.
            if !obj.contains_key("result") && !obj.contains_key("error") {
                let id = obj.get("id").filter(|i| i.is_string() || i.is_i64() || i.is_u64()).cloned().unwrap_or(Value::Null);
                send(&out, error(id, INVALID_REQUEST, "Invalid Request: no method", None));
            }
            continue;
        };
        if obj.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
            // A notification gets no answer, even an error (JSON-RPC 2.0).
            if let Some(id) = obj.get("id").filter(|i| i.is_string() || i.is_i64() || i.is_u64()) {
                send(&out, error(id.clone(), INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"", None));
            }
            continue;
        }
        let params = obj.get("params").cloned().unwrap_or(Value::Null);
        let Some(id) = obj.get("id").cloned() else {
            if method == "notifications/cancelled" {
                if let Some(rid) = params.get("requestId") {
                    if let Some(c) = in_flight.lock().unwrap().get_mut(&rid.to_string()) {
                        *c = true;
                    }
                }
            }
            continue; // notifications/initialized and others need no answer
        };
        if !(id.is_string() || id.is_i64() || id.is_u64()) {
            send(&out, error(Value::Null, INVALID_REQUEST, "Invalid Request: the id must be a string or an integer", None));
            continue;
        }
        // JSON-RPC: params is an object or an array; MCP requests take an object.
        match &params {
            Value::Null | Value::Object(_) => {}
            Value::Array(_) => {
                send(&out, error(id, INVALID_PARAMS, "Invalid params: MCP requests take an object", None));
                continue;
            }
            _ => {
                send(&out, error(id, INVALID_REQUEST, "Invalid Request: params must be an object", None));
                continue;
            }
        }
        let key = id.to_string();
        {
            // Check and claim the id under one lock, without touching a running request's entry.
            let mut flight = in_flight.lock().unwrap();
            if flight.contains_key(&key) {
                drop(flight);
                send(&out, error(id, INVALID_REQUEST, "Invalid Request: this id is already in use by a running request", None));
                continue;
            }
            flight.insert(key.clone(), false);
        }
        let (server, out, in_flight) = (server.clone(), out.clone(), in_flight.clone());
        workers.push(std::thread::spawn(move || {
            let response = server.handle(id, &method, &params);
            // A cancelled request gets no further messages (MCP cancellation).
            let mut flight = in_flight.lock().unwrap();
            if !flight.remove(&key).unwrap_or(false) {
                send(&out, response);
            }
        }));
        workers.retain(|w| !w.is_finished());
    }
    // stdin closed: the client is shutting us down. Stop the engines unless a call still runs; the engines
    // then exit on their own when their stdin closes with this process.
    if in_flight.lock().unwrap().is_empty() {
        server.engines.shutdown();
    }
    workers
}

fn send<W: Write>(out: &Mutex<W>, msg: Value) {
    let mut w = out.lock().unwrap();
    let _ = writeln!(w, "{msg}");
    let _ = w.flush();
}

fn error(id: Value, code: i64, message: &str, data: Option<Value>) -> Value {
    let mut e = json!({ "code": code, "message": message });
    if let Some(d) = data {
        e["data"] = d;
    }
    json!({ "jsonrpc": "2.0", "id": id, "error": e })
}

fn server_info() -> Value {
    json!({ "name": "cpi-workbench", "title": "CPI Workbench", "version": env!("CARGO_PKG_VERSION") })
}

fn capabilities() -> Value {
    json!({ "tools": {}, "prompts": {} })
}

fn supported_versions() -> Vec<&'static str> {
    std::iter::once(MODERN).chain(LEGACY.iter().copied()).collect()
}

impl Server {
    pub fn new(engines: Arc<Engines>, project: Option<PathBuf>) -> Self {
        Server { engines, project, runs: runs::Runs::default(), writes: Mutex::new(()) }
    }

    pub fn engines(&self) -> &Engines {
        &self.engines
    }

    pub fn runs(&self) -> &runs::Runs {
        &self.runs
    }

    /// The lock save_file and import_iflow hold over "does it exist?" and the write.
    pub fn write_lock(&self) -> std::sync::MutexGuard<'_, ()> {
        self.writes.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn project(&self) -> Option<&Path> {
        self.project.as_deref()
    }

    fn handle(&self, id: Value, method: &str, params: &Value) -> Value {
        let meta = params.get("_meta");
        let requested = meta.and_then(|m| m.get(META_VERSION)).and_then(Value::as_str);
        // server/discover exists only in 2026-07-28, so it always needs that revision's _meta.
        if method == "server/discover" && requested.is_none() {
            return error(id, INVALID_PARAMS, &format!("Invalid params: server/discover needs _meta \"{META_VERSION}\" and \"{META_CAPABILITIES}\""), None);
        }
        let modern = requested.is_some();
        if let Some(v) = requested {
            if v != MODERN && method != "initialize" {
                return error(id, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                    Some(json!({ "supported": supported_versions(), "requested": v })));
            }
            // 2026-07-28: every request carries its capabilities ({} for none).
            if method != "initialize" && !meta.and_then(|m| m.get(META_CAPABILITIES)).is_some_and(Value::is_object) {
                return error(id, INVALID_PARAMS, &format!("Invalid params: _meta needs \"{META_CAPABILITIES}\" (an object)"), None);
            }
        }
        let result = match method {
            "initialize" => Ok(initialize(params)),
            "ping" => Ok(json!({})),
            "server/discover" => Ok(json!({
                "supportedVersions": supported_versions(),
                "capabilities": capabilities(),
                "instructions": INSTRUCTIONS,
                "ttlMs": LIST_TTL_MS,
                "cacheScope": "public",
            })),
            "tools/list" => {
                let mut r = json!({ "tools": tools::definitions() });
                if modern {
                    r["ttlMs"] = json!(LIST_TTL_MS);
                    r["cacheScope"] = json!("public");
                }
                Ok(r)
            }
            "tools/call" => self.call_tool(params),
            "prompts/list" => {
                let mut r = json!({ "prompts": prompt_definitions() });
                if modern {
                    r["ttlMs"] = json!(LIST_TTL_MS);
                    r["cacheScope"] = json!("public");
                }
                Ok(r)
            }
            "prompts/get" => get_prompt(params),
            _ => Err((METHOD_NOT_FOUND, format!("Method not found: {method}"))),
        };
        match result {
            Ok(mut r) => {
                if modern || method == "server/discover" {
                    r["resultType"] = json!("complete");
                    r["_meta"] = json!({ META_SERVER: server_info() });
                }
                json!({ "jsonrpc": "2.0", "id": id, "result": r })
            }
            Err((code, message)) => error(id, code, &message, None),
        }
    }

    fn call_tool(&self, params: &Value) -> Result<Value, (i64, String)> {
        let name = params.get("name").and_then(Value::as_str)
            .ok_or((INVALID_PARAMS, "tools/call needs a tool name".to_string()))?;
        let args = match params.get("arguments") {
            None | Some(Value::Null) => Map::new(),
            Some(Value::Object(m)) => m.clone(),
            Some(_) => return Err((INVALID_PARAMS, "arguments must be an object".into())),
        };
        if !tools::exists(name) {
            return Err((INVALID_PARAMS, format!("Unknown tool: {name}")));
        }
        // Wrong arguments and failed runs are tool results with isError, so the model can correct them.
        Ok(match tools::call(self, name, &args) {
            Ok(v) => tool_result(v),
            Err(e) => json!({ "content": [{ "type": "text", "text": e }], "isError": true }),
        })
    }
}

/// Structured content plus the same JSON as text, for clients that read only `content` (MCP Tools).
fn tool_result(v: Value) -> Value {
    let text = serde_json::to_string_pretty(&v).unwrap_or_default();
    json!({ "content": [{ "type": "text", "text": text }], "structuredContent": v, "isError": false })
}

/// Legacy handshake: the client's version if this server knows it, else the newest legacy one.
fn initialize(params: &Value) -> Value {
    let asked = params.get("protocolVersion").and_then(Value::as_str).unwrap_or("");
    let version = LEGACY.iter().find(|v| **v == asked).copied().unwrap_or(LEGACY[0]);
    json!({
        "protocolVersion": version,
        "capabilities": capabilities(),
        "serverInfo": server_info(),
        "instructions": INSTRUCTIONS,
    })
}

#[cfg(test)]
mod tests;
