//! Supervises the engine processes. Each runtime profile is a separate process speaking
//! newline-delimited JSON-RPC over stdin/stdout:
//!   * XSLT profiles: native executables (no JVM),
//!   * Groovy profiles: jars on a JRE (bundled `jre/` or the system Java 17+).
//! Engines start lazily on first use, restart transparently after they exit (e.g. after a timed-out
//! call) and JVM engines stop after a period of inactivity to give memory back.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub enum Kind {
    Native,
    Jar,
}

/// Runtime profile id → engine base name and kind.
pub const PROFILES: &[(&str, &str, Kind)] = &[
    ("saxon-he-9.9", "cpiwb-xml-he99", Kind::Native),
    ("saxon-he-12", "cpiwb-xml-he12", Kind::Native),
    ("groovy-1.x", "cpiwb-groovy-v1", Kind::Jar),
    ("groovy-2.x", "cpiwb-groovy-v2", Kind::Jar),
];

/// JVM engines that were not used for this long are stopped.
pub const IDLE_LIMIT: Duration = Duration::from_secs(10 * 60);

struct Process {
    child: Child,
    stdin: Option<ChildStdin>,
    stdout: BufReader<ChildStdout>,
    next_id: u64,
    last_used: Instant,
    jvm: bool,
}

impl Drop for Process {
    fn drop(&mut self) {
        // Closing stdin lets a JVM exit normally (and write its class-data archive); kill as fallback.
        drop(self.stdin.take());
        let deadline = Instant::now() + Duration::from_millis(if self.jvm { 3000 } else { 300 });
        while Instant::now() < deadline {
            if let Ok(Some(_)) = self.child.try_wait() {
                return;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// How to start an engine.
#[derive(Clone, Debug)]
pub enum Launch {
    Native(PathBuf),
    Jar { java: PathBuf, jar: PathBuf },
}

#[derive(Default)]
pub struct Engines {
    slots: Mutex<HashMap<String, Arc<Mutex<Option<Process>>>>>,
    /// Bundled resources (installed app): jars and the JRE live here.
    pub resource_dir: Mutex<Option<PathBuf>>,
    /// Where JVM class-data archives (AppCDS) are kept for faster starts.
    pub cache_dir: Mutex<Option<PathBuf>>,
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
                *p = None;
            }
        }
    }

    /// Stops JVM engines that have been idle longer than `limit`.
    pub fn stop_idle(&self, limit: Duration) {
        let slots: Vec<_> = self.slots.lock().unwrap().values().cloned().collect();
        for slot in slots {
            if let Ok(mut guard) = slot.try_lock() {
                let idle = guard.as_ref().map(|p| p.jvm && p.last_used.elapsed() > limit).unwrap_or(false);
                if idle {
                    *guard = None;
                }
            }
        }
    }

    pub fn locate(&self, profile: &str) -> Option<Launch> {
        locate(profile, self.resource_dir.lock().unwrap().as_deref())
    }

    pub fn available(&self) -> Vec<String> {
        PROFILES.iter().filter(|(p, _, _)| self.locate(p).is_some()).map(|(p, _, _)| p.to_string()).collect()
    }

    /// Sends one request and waits for its response. Blocking; call from a worker thread.
    pub fn call(&self, profile: &str, method: &str, params: Value) -> Result<Value, String> {
        let launch = self.locate(profile).ok_or_else(|| missing_message(profile))?;
        let slot = self.slot(profile);
        let mut guard = slot.lock().map_err(|_| "engine lock poisoned".to_string())?;
        if guard.is_none() {
            let cache = self.cache_dir.lock().unwrap().clone();
            *guard = Some(start(&launch, profile, cache.as_deref())?);
        }
        let proc = guard.as_mut().unwrap();
        proc.last_used = Instant::now();
        let result = exchange(proc, method, params);
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

fn missing_message(profile: &str) -> String {
    match PROFILES.iter().find(|(p, _, _)| *p == profile) {
        Some((_, base, Kind::Native)) => format!(
            "The engine for profile '{profile}' is not installed. Expected {} next to the application.", exe_name(base)),
        Some((_, base, Kind::Jar)) => format!(
            "The Groovy engine for '{profile}' is not available. It needs {base}.jar and a Java 17+ runtime \
             (bundled jre/ folder, JAVA_HOME or java on PATH)."),
        None => format!("Unknown profile '{profile}'"),
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
    let stdin = p.stdin.as_mut().ok_or("engine stdin closed")?;
    stdin.write_all(line.as_bytes()).map_err(|e| e.to_string())?;
    stdin.write_all(b"\n").map_err(|e| e.to_string())?;
    stdin.flush().map_err(|e| e.to_string())?;
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

fn start(launch: &Launch, profile: &str, cache_dir: Option<&Path>) -> Result<Process, String> {
    let jvm = matches!(launch, Launch::Jar { .. });
    let mut cmd = match launch {
        Launch::Native(path) => Command::new(path),
        Launch::Jar { java, jar } => {
            let mut c = Command::new(java);
            c.arg("-XX:+UseSerialGC").arg("-Xmx512m").arg("-Xss4m").arg("-Xshare:auto")
                // JVM log output must never reach stdout (the protocol channel).
                .arg("-Xlog:disable").arg("-Xlog:all=warning:stderr");
            // AppCDS: reuse the class-data archive of an earlier run, or create it on exit.
            if let Some(dir) = cache_dir {
                let _ = std::fs::create_dir_all(dir);
                let archive = dir.join(format!("{profile}-{}.jsa", file_stamp(jar)));
                if archive.is_file() {
                    c.arg(format!("-XX:SharedArchiveFile={}", archive.display()));
                } else {
                    c.arg(format!("-XX:ArchiveClassesAtExit={}", archive.display()));
                }
            }
            c.arg("-jar").arg(jar);
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
            let code = child.try_wait().ok().flatten().and_then(|s| s.code());
            return Err(format!("Engine {launch:?} exited during start (exit code {code:?})"));
        }
        if let Ok(v) = serde_json::from_str::<Value>(buf.trim()) {
            if v.get("event").and_then(Value::as_str) == Some("ready") {
                break;
            }
        }
    }
    Ok(Process { child, stdin: Some(stdin), stdout, next_id: 0, last_used: Instant::now(), jvm })
}

/// Size+mtime of the jar, so a new engine version gets a new class-data archive.
fn file_stamp(path: &Path) -> String {
    std::fs::metadata(path)
        .map(|m| {
            let t = m.modified().ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            format!("{:x}-{:x}", m.len(), t)
        })
        .unwrap_or_else(|_| "0".into())
}

fn profile(profile: &str) -> Option<&'static (&'static str, &'static str, Kind)> {
    PROFILES.iter().find(|(p, _, _)| *p == profile)
}

fn exe_name(base: &str) -> String {
    if cfg!(windows) { format!("{base}.exe") } else { base.to_string() }
}

/// Finds the engine for a profile.
/// Native: CPIWB_ENGINE_DIR, next to the app executable (bundled sidecar), repository build.
/// Jar: CPIWB_ENGINE_DIR, bundled resources, repository build — plus a Java runtime.
pub fn locate(name: &str, resource_dir: Option<&Path>) -> Option<Launch> {
    let (_, base, kind) = profile(name)?;
    let app_dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    let env_dir = std::env::var("CPIWB_ENGINE_DIR").ok().map(PathBuf::from);
    match kind {
        Kind::Native => {
            let exe = exe_name(base);
            for dir in env_dir.iter().chain(std::iter::once(&app_dir)) {
                let p = dir.join(&exe);
                if p.is_file() {
                    return Some(Launch::Native(p));
                }
            }
            let id = base.trim_start_matches("cpiwb-xml-");
            for dir in app_dir.ancestors() {
                let native = dir.join("engine").join("native").join("out").join(&exe);
                if native.is_file() {
                    return Some(Launch::Native(native));
                }
                let jar = dir.join("engine").join(format!("xml-{id}")).join("target").join(format!("{base}.jar"));
                if jar.is_file() {
                    return java_binary(resource_dir).map(|java| Launch::Jar { java, jar });
                }
            }
            None
        }
        Kind::Jar => {
            let file = format!("{base}.jar");
            let mut candidates: Vec<PathBuf> = Vec::new();
            if let Some(d) = &env_dir { candidates.push(d.join(&file)); }
            if let Some(d) = resource_dir {
                candidates.push(d.join(&file));
                candidates.push(d.join("engines").join(&file));
            }
            candidates.push(app_dir.join(&file));
            let id = base.trim_start_matches("cpiwb-groovy-");
            for dir in app_dir.ancestors() {
                candidates.push(dir.join("engine").join(format!("groovy-{id}")).join("target").join(&file));
            }
            let jar = candidates.into_iter().find(|p| p.is_file())?;
            java_binary(resource_dir).map(|java| Launch::Jar { java, jar })
        }
    }
}

/// Bundled JRE first, then JAVA_HOME, then `java` on PATH (only if it runs).
fn java_binary(resource_dir: Option<&Path>) -> Option<PathBuf> {
    let java = exe_name("java");
    if let Some(d) = resource_dir {
        let p = d.join("jre").join("bin").join(&java);
        if p.is_file() {
            return Some(p);
        }
    }
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let p = Path::new(&home).join("bin").join(&java);
        if p.is_file() {
            return Some(p);
        }
    }
    let ok = Command::new(&java).arg("-version").stdout(Stdio::null()).stderr(Stdio::null())
        .status().map(|s| s.success()).unwrap_or(false);
    if ok { Some(PathBuf::from(java)) } else { None }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_names() {
        assert_eq!(profile("saxon-he-9.9").map(|p| p.1), Some("cpiwb-xml-he99"));
        assert_eq!(profile("groovy-2.x").map(|p| p.1), Some("cpiwb-groovy-v2"));
        assert!(profile("nope").is_none());
    }

    /// Runs against real engines when found (repository checkout with a build).
    #[test]
    fn round_trip_when_engines_present() {
        let engines = Engines::default();
        if engines.locate("saxon-he-9.9").is_some() {
            let info = engines.call("saxon-he-9.9", "engine.info", json!({})).unwrap();
            assert_eq!(info["profile"], "saxon-he-9.9");
            let r = engines.call("saxon-he-9.9", "xslt.run", json!({
                "xslt": {"content": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:param name='h'/><xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='$h'/></xsl:template></xsl:stylesheet>"},
                "input": {"body": "<a/>", "headers": [{"name": "h", "value": "hello", "type": "String"}]}
            })).unwrap();
            assert_eq!(r["output"]["body"], "hello");
            let err = engines.call("saxon-he-9.9", "no.such", json!({})).unwrap_err();
            assert!(err.contains("Unknown method"));
        }
        if engines.locate("groovy-1.x").is_some() {
            let r = engines.call("groovy-1.x", "groovy.run", json!({
                "script": {"content": "def processData(m) { m.setBody(m.getBody(String).reverse()); m }"},
                "input": {"body": "abc"}
            })).unwrap();
            assert_eq!(r["output"]["body"], "cba", "{r}");
        }
        engines.stop_idle(Duration::from_secs(0));
        engines.shutdown();
    }

    /// The installed app's path through the shell: every engine over stdio, on a copy of the demo
    /// project in the OS temp folder (Windows paths on Windows). Skipped unless CPIWB_REQUIRE_ENGINES
    /// is set; CI sets it with CPIWB_ENGINE_DIR (native executables, jars) and JAVA_HOME (bundled JRE).
    #[test]
    fn all_engines_run_the_demo_project() {
        if std::env::var_os("CPIWB_REQUIRE_ENGINES").is_none() {
            return;
        }
        let demo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../examples/demo-project");
        let project = std::env::temp_dir().join(format!("cpiwb-demo-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&project);
        copy_dir(&demo, &project);
        let cases = count_cases(&project.join("tests"));
        assert!(cases > 0);

        let engines = Engines::default();
        let mut passed = std::collections::BTreeSet::new();
        for (name, _, _) in PROFILES {
            assert!(engines.locate(name).is_some(), "{}", missing_message(name));
            let info = engines.call(name, "engine.info", json!({})).unwrap();
            assert_eq!(info["profile"], *name, "{info}");
            let opened = engines.call(name, "project.open", json!({ "dir": project.to_string_lossy() })).unwrap();
            let files = opened["files"].as_array().unwrap();
            assert!(files.iter().any(|f| f["path"] == "tests/groovy-attachment/case.json"),
                "project paths use '/': {opened}");
            let r = engines.call(name, "tests.run", json!({})).unwrap();
            assert_eq!(r["failed"], 0, "{name}: {r}");
            for c in r["results"].as_array().unwrap() {
                if c["status"] == "passed" {
                    passed.insert(c["dir"].as_str().unwrap().replace('\\', "/"));
                }
            }
        }
        // Cases without a profile (XSD) run on both XML engines; every case passes somewhere.
        assert_eq!(passed.len() as u64, cases, "cases passed on some engine: {passed:?}");

        // Files written through the engine land in the project, nested folders included.
        let w = engines.call("saxon-he-9.9", "file.write",
            json!({ "path": "tests/new-case/input.xml", "content": "<a/>" })).unwrap();
        assert_eq!(w["saved"], true);
        assert!(project.join("tests").join("new-case").join("input.xml").is_file());
        engines.shutdown();
        let _ = std::fs::remove_dir_all(&project);
    }

    fn copy_dir(from: &Path, to: &Path) {
        std::fs::create_dir_all(to).unwrap();
        for e in std::fs::read_dir(from).unwrap() {
            let e = e.unwrap();
            let target = to.join(e.file_name());
            if e.file_type().unwrap().is_dir() {
                copy_dir(&e.path(), &target);
            } else {
                std::fs::copy(e.path(), target).unwrap();
            }
        }
    }

    fn count_cases(dir: &Path) -> u64 {
        std::fs::read_dir(dir).unwrap().filter_map(Result::ok)
            .filter(|e| e.path().join("case.json").is_file()).count() as u64
    }
}
