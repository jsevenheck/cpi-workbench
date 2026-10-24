//! Supervises the engine processes. Each runtime profile (Saxon 9.9, Saxon 12, later Groovy) is a
//! separate executable speaking newline-delimited JSON-RPC over stdin/stdout. Engines start lazily
//! on first use and are restarted transparently after they exit (e.g. after a timed-out call).

use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::{Arc, Mutex};

/// Runtime profile id → engine executable base name.
pub const PROFILES: &[(&str, &str)] = &[
    ("saxon-he-9.9", "cpiwb-xml-he99"),
    ("saxon-he-12", "cpiwb-xml-he12"),
];

struct Process {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next_id: u64,
}

impl Drop for Process {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// How to start an engine: a native executable, or a jar run on a JVM (development fallback).
#[derive(Clone, Debug)]
pub enum Launch {
    Native(PathBuf),
    Jar { java: PathBuf, jar: PathBuf },
}

#[derive(Default)]
pub struct Engines {
    slots: Mutex<HashMap<String, Arc<Mutex<Option<Process>>>>>,
}

impl Engines {
    fn slot(&self, profile: &str) -> Arc<Mutex<Option<Process>>> {
        let mut slots = self.slots.lock().unwrap();
        slots.entry(profile.to_string()).or_default().clone()
    }

    pub fn shutdown(&self) {
        let slots = self.slots.lock().unwrap();
        for slot in slots.values() {
            if let Ok(mut p) = slot.lock() {
                *p = None; // Drop kills the child
            }
        }
    }

    /// Sends one request and waits for its response. Blocking; call from a worker thread.
    pub fn call(&self, profile: &str, method: &str, params: Value) -> Result<Value, String> {
        let launch = locate(profile).ok_or_else(|| format!(
            "The engine for profile '{profile}' is not installed. Expected {} next to the application.",
            exe_name(base_name(profile).unwrap_or("?"))))?;
        let slot = self.slot(profile);
        let mut guard = slot.lock().map_err(|_| "engine lock poisoned".to_string())?;
        if guard.is_none() {
            *guard = Some(start(&launch)?);
        }
        let result = exchange(guard.as_mut().unwrap(), method, params);
        match result {
            Ok(Exchange::Result(v)) => Ok(v),
            Ok(Exchange::Error { message, timeout }) => {
                if timeout {
                    *guard = None; // engine exits after a timeout; start a fresh one next time
                }
                Err(message)
            }
            Err(e) => {
                *guard = None;
                Err(format!("Engine stopped unexpectedly: {e}"))
            }
        }
    }
}

enum Exchange {
    Result(Value),
    Error { message: String, timeout: bool },
}

fn exchange(p: &mut Process, method: &str, params: Value) -> Result<Exchange, String> {
    p.next_id += 1;
    let id = p.next_id;
    let line = json!({ "id": id, "method": method, "params": params }).to_string();
    p.stdin.write_all(line.as_bytes()).map_err(|e| e.to_string())?;
    p.stdin.write_all(b"\n").map_err(|e| e.to_string())?;
    p.stdin.flush().map_err(|e| e.to_string())?;
    loop {
        let mut buf = String::new();
        let n = p.stdout.read_line(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            let code = p.child.try_wait().ok().flatten().and_then(|s| s.code());
            return Err(format!("no response (exit code {code:?})"));
        }
        let v: Value = match serde_json::from_str(buf.trim()) {
            Ok(v) => v,
            Err(_) => continue, // not protocol output
        };
        if v.get("id").and_then(Value::as_u64) != Some(id) {
            continue; // events or stale responses
        }
        if let Some(err) = v.get("error") {
            let message = err.get("message").and_then(Value::as_str).unwrap_or("engine error").to_string();
            let timeout = err.get("timeout").and_then(Value::as_bool).unwrap_or(false);
            return Ok(Exchange::Error { message, timeout });
        }
        return Ok(Exchange::Result(v.get("result").cloned().unwrap_or(Value::Null)));
    }
}

fn start(launch: &Launch) -> Result<Process, String> {
    let mut cmd = match launch {
        Launch::Native(path) => Command::new(path),
        Launch::Jar { java, jar } => {
            let mut c = Command::new(java);
            c.arg("-XX:+UseSerialGC").arg("-Xshare:auto").arg("-jar").arg(jar);
            c
        }
    };
    cmd.arg("serve").arg("--stdio")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = cmd.spawn().map_err(|e| format!("Cannot start engine {launch:?}: {e}"))?;
    let stdin = child.stdin.take().ok_or("no stdin")?;
    let mut stdout = BufReader::new(child.stdout.take().ok_or("no stdout")?);
    // Wait for the ready event so the first call does not race the engine start.
    loop {
        let mut buf = String::new();
        if stdout.read_line(&mut buf).map_err(|e| e.to_string())? == 0 {
            return Err(format!("Engine {launch:?} exited during start"));
        }
        if let Ok(v) = serde_json::from_str::<Value>(buf.trim()) {
            if v.get("event").and_then(Value::as_str) == Some("ready") {
                break;
            }
        }
    }
    Ok(Process { child, stdin, stdout, next_id: 0 })
}

fn base_name(profile: &str) -> Option<&'static str> {
    PROFILES.iter().find(|(p, _)| *p == profile).map(|(_, n)| *n)
}

fn exe_name(base: &str) -> String {
    if cfg!(windows) { format!("{base}.exe") } else { base.to_string() }
}

/// Finds the engine for a profile: CPIWB_ENGINE_DIR, next to the app executable (bundled
/// sidecar), or — when running from the source tree — the native build or the jar.
pub fn locate(profile: &str) -> Option<Launch> {
    let base = base_name(profile)?;
    let exe = exe_name(base);
    if let Ok(dir) = std::env::var("CPIWB_ENGINE_DIR") {
        let p = Path::new(&dir).join(&exe);
        if p.is_file() {
            return Some(Launch::Native(p));
        }
    }
    let app_dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    let bundled = app_dir.join(&exe);
    if bundled.is_file() {
        return Some(Launch::Native(bundled));
    }
    // Development: walk up to the repository root.
    let id = base.trim_start_matches("cpiwb-xml-");
    for dir in app_dir.ancestors() {
        let native = dir.join("engine").join("native").join("out").join(&exe);
        if native.is_file() {
            return Some(Launch::Native(native));
        }
        let jar = dir.join("engine").join(format!("xml-{id}")).join("target").join(format!("{base}.jar"));
        if jar.is_file() {
            return Some(Launch::Jar { java: java_binary(), jar });
        }
    }
    None
}

fn java_binary() -> PathBuf {
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let p = Path::new(&home).join("bin").join(exe_name("java"));
        if p.is_file() {
            return p;
        }
    }
    PathBuf::from(exe_name("java"))
}

pub fn available() -> Vec<String> {
    PROFILES.iter().filter(|(p, _)| locate(p).is_some()).map(|(p, _)| p.to_string()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_names() {
        assert_eq!(base_name("saxon-he-9.9"), Some("cpiwb-xml-he99"));
        assert_eq!(base_name("nope"), None);
    }

    /// Runs against a real engine when one is found (repository checkout with a build).
    #[test]
    fn round_trip_when_engine_present() {
        if locate("saxon-he-9.9").is_none() {
            return;
        }
        let engines = Engines::default();
        let info = engines.call("saxon-he-9.9", "engine.info", json!({})).unwrap();
        assert_eq!(info["profile"], "saxon-he-9.9");
        let r = engines.call("saxon-he-9.9", "xslt.run", json!({
            "xslt": {"content": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:param name='h'/><xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='$h'/></xsl:template></xsl:stylesheet>"},
            "input": {"body": "<a/>", "headers": [{"name": "h", "value": "hello", "type": "String"}]}
        })).unwrap();
        assert_eq!(r["output"]["body"], "hello");
        let err = engines.call("saxon-he-9.9", "no.such", json!({})).unwrap_err();
        assert!(err.contains("Unknown method"));
        engines.shutdown();
    }
}
