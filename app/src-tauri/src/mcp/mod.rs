//! `cpi-workbench mcp`: a Model Context Protocol server over stdio, so that a coding agent can run
//! mappings, scripts and flow steps on the simulator while it writes them.
//!
//! * Transport: stdio, one JSON-RPC 2.0 message per line (MCP "stdio" transport). Logs go to stderr.
//! * Protocol: dual-era as the 2026-07-28 revision describes it ("Versioning: Backward Compatibility").
//!   A request that carries `_meta["io.modelcontextprotocol/protocolVersion"]` is served statelessly
//!   under 2026-07-28 (`server/discover`, `resultType`, caching hints); a client that opens with
//!   `initialize` gets the negotiated legacy revision (2025-11-25 back to 2024-11-05).
//! * Features: tools only, one per tab of the app (see `tools.rs`); the app's features lead and the
//!   tools add none of their own. No resources, prompts, logging, sampling or roots.
//! * Scope: one project folder (`--project`; default `CLAUDE_PROJECT_DIR`, else the working directory).
//!   Files are read through the engines, which refuse paths outside the project. Groovy scripts run without a sandbox, as in
//!   the app; docs/mcp.md says so.

mod flow;
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
XSLT mappings (Saxon-HE 9.9 like CPI, or 12), Groovy scripts (Script API 1.x on Groovy 2.4 / Camel 2.24, \
2.x on Groovy 4 / Camel 3.14), XSD validation, XPath and the flow steps Content Modifier, Filter, Router, \
Splitter and Gather, as the tabs of the CPI Workbench app do. Paths are relative to the project folder; files outside it cannot be \
read. Call project_info first to see the project's files, iFlow steps and installed runtimes. The simulator \
is not a tenant: messages starting with 'UnsupportedInSimulator' name behaviour it does not simulate, and \
docs/fidelity.md in the CPI Workbench repository lists what is verified against CPI.";

pub struct Server {
    engines: Arc<Engines>,
    project: Option<PathBuf>,
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
        let key = id.to_string();
        if in_flight.lock().unwrap().insert(key.clone(), false).is_some() {
            send(&out, error(id, INVALID_REQUEST, "Invalid Request: this id is already in use by a running request", None));
            continue;
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

fn supported_versions() -> Vec<&'static str> {
    std::iter::once(MODERN).chain(LEGACY.iter().copied()).collect()
}

impl Server {
    pub fn new(engines: Arc<Engines>, project: Option<PathBuf>) -> Self {
        Server { engines, project }
    }

    pub fn engines(&self) -> &Engines {
        &self.engines
    }

    pub fn project(&self) -> Option<&Path> {
        self.project.as_deref()
    }

    fn handle(&self, id: Value, method: &str, params: &Value) -> Value {
        let meta = params.get("_meta");
        let requested = meta.and_then(|m| m.get(META_VERSION)).and_then(Value::as_str);
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
                "capabilities": { "tools": {} },
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
        "capabilities": { "tools": {} },
        "serverInfo": server_info(),
        "instructions": INSTRUCTIONS,
    })
}

#[cfg(test)]
mod tests;
