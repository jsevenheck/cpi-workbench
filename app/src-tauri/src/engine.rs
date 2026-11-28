//! Supervises the engine processes. Each runtime profile is a separate process speaking
//! newline-delimited JSON-RPC over stdin/stdout:
//!   * XSLT profiles: native executables (no JVM),
//!   * Groovy profiles: jars on a JRE (bundled `jre/`, Java 21 like a tenant, or the system Java 17+).
//!
//! Engines start lazily on first use, restart transparently after they exit (e.g. after a timed-out
//! call) and JVM engines stop after a period of inactivity to give memory back.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender};
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

const MAX_FRAME: usize = 192 * 1024 * 1024;
const POLL: Duration = Duration::from_millis(25);

#[derive(Clone, Copy)]
struct Deadlines {
    queue: Duration,
    startup: Duration,
    exchange: Duration,
    /// Largest request or response line, newline included.
    frame: usize,
}
const STARTUP: u64 = 15;
const EXCHANGE: u64 = 32; // Java normally diagnoses execution timeouts at 30 seconds
const DEADLINES: Deadlines = Deadlines {
    // A call waiting for its turn outlives the longest call ahead of it (a fresh worker's startup plus its
    // exchange), so a file read or save during a long run waits for it instead of failing as busy.
    queue: Duration::from_secs(STARTUP + EXCHANGE + 3),
    startup: Duration::from_secs(STARTUP),
    exchange: Duration::from_secs(EXCHANGE),
    frame: MAX_FRAME,
};

type Reply = Result<Value, String>;

struct Process {
    child: Child,
    requests: Option<SyncSender<(u64, Vec<u8>)>>,
    replies: Receiver<Reply>,
    next_id: u64,
    last_used: Instant,
    jvm: bool,
}

impl Drop for Process {
    fn drop(&mut self) {
        // Closing stdin lets a JVM exit normally (and write its class-data archive); kill as fallback.
        drop(self.requests.take());
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
    #[cfg(test)]
    Fixture(&'static str),
}

#[derive(Default)]
pub struct Engines {
    slots: Mutex<HashMap<String, Arc<Mutex<Option<Process>>>>>,
    stopping: AtomicBool,
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
        self.stopping.store(true, Ordering::Release);
        let slots: Vec<_> = self.slots.lock().unwrap().values().cloned().collect();
        for slot in slots {
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

    /// The desktop sends its project with every call, so a fresh worker needs no separate initialization.
    /// The distinct envelope method makes an older worker fail rather than run with the wrong context.
    pub fn call_desktop(&self, profile: &str, method: &str, params: Value, project_root: Option<String>) -> Result<Value, String> {
        self.call(profile, "project.request", json!({ "method": method, "params": params, "projectRoot": project_root }))
            .map_err(|e| if e == "Unknown method 'project.request'" {
                "The engine does not support scoped desktop project requests. Use an engine built with this app.".into()
            } else { e })
    }

    /// Sends one request and waits for its response. Blocking; call from a worker thread.
    pub fn call(&self, profile: &str, method: &str, params: Value) -> Result<Value, String> {
        let launch = self.locate(profile).ok_or_else(|| missing_message(profile))?;
        self.call_with(profile, &launch, method, params, DEADLINES)
    }

    fn call_with(&self, profile: &str, launch: &Launch, method: &str, params: Value, limits: Deadlines) -> Result<Value, String> {
        let slot = self.slot(profile);
        let queue_deadline = Instant::now() + limits.queue;
        let mut guard = loop {
            if self.stopping.load(Ordering::Acquire) { return Err("Engine supervisor is shutting down".into()); }
            match slot.try_lock() {
                Ok(guard) => break guard,
                Err(std::sync::TryLockError::Poisoned(_)) => return Err("engine lock poisoned".into()),
                Err(std::sync::TryLockError::WouldBlock) => {
                    let remaining = queue_deadline.saturating_duration_since(Instant::now());
                    if remaining.is_zero() { return Err(format!("Engine busy ({profile}): timed out waiting for a turn. The request did not run.")); }
                    std::thread::sleep(remaining.min(POLL));
                }
            }
        };
        if self.stopping.load(Ordering::Acquire) { return Err("Engine supervisor is shutting down".into()); }
        // Retire an already-exited child before handing it a request. Once sent, calls are never replayed.
        if guard.as_mut().is_some_and(|p| matches!(p.child.try_wait(), Ok(Some(_)))) {
            *guard = None;
        }
        if guard.is_none() {
            let cache = self.cache_dir.lock().unwrap().clone();
            let deadline = Instant::now() + limits.startup;
            let proc = start(launch, profile, cache.as_deref(), deadline, &self.stopping, limits.frame)?;
            *guard = Some(proc);
        }
        let proc = guard.as_mut().unwrap();
        proc.last_used = Instant::now();
        let result = exchange(proc, method, params, Instant::now() + limits.exchange, &self.stopping, limits.frame);
        proc.last_used = Instant::now();
        match result {
            Ok(Exchange::Result(v)) => Ok(v),
            Ok(Exchange::Error { message, timeout }) => {
                if timeout {
                    *guard = None; // engine exits after a timeout; start a fresh one next time
                }
                Err(message)
            }
            Err(e) => {
                // The pipe is out of step (or gone): the worker cannot take another request.
                proc.terminate();
                *guard = None;
                if e.starts_with("UnsupportedInSimulator") {
                    Err(format!("{e} The engine ({profile}) was stopped; the next call starts a fresh one."))
                } else {
                    Err(format!("Engine transport ({profile}): {e}"))
                }
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

impl Process {
    fn terminate(&mut self) {
        drop(self.requests.take());
        let _ = self.child.kill();
    }

    fn wait_reply(&mut self, deadline: Instant, stopping: &AtomicBool, stage: &str) -> Reply {
        loop {
            if stopping.load(Ordering::Acquire) { return Err("engine supervisor is shutting down".into()); }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() { return Err(format!("timeout {stage}; the worker was stopped. The request was not retried.")); }
            match self.replies.recv_timeout(left.min(POLL)) {
                Ok(Ok(v)) => return Ok(v),
                // A simulator limit (an oversized frame) is its own message, not a transport failure.
                Ok(Err(e)) if e.starts_with("UnsupportedInSimulator") => return Err(e),
                Ok(Err(e)) => {
                    let code = self.child.try_wait().ok().flatten().and_then(|s| s.code());
                    return Err(format!("{e} {stage} (exit code {code:?})"));
                }
                Err(RecvTimeoutError::Disconnected) => return Err(format!("engine pipe closed {stage}")),
                Err(RecvTimeoutError::Timeout) => {}
            }
        }
    }
}

/// `Err` is a transport failure (the caller stops the worker). A request that is too large to send is
/// refused before anything is written, as an engine error: the worker stays as it is.
fn exchange(p: &mut Process, method: &str, params: Value, deadline: Instant, stopping: &AtomicBool, max: usize) -> Result<Exchange, String> {
    p.next_id += 1;
    let id = p.next_id;
    struct Frame(Vec<u8>, usize);
    impl Write for Frame {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            if bytes.len() >= self.1 - self.0.len() { return Err(std::io::Error::other("engine frame limit")); }
            self.0.extend_from_slice(bytes);
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
    }
    let mut frame = Frame(Vec::new(), max);
    if serde_json::to_writer(&mut frame, &json!({ "id": id, "method": method, "params": params })).is_err() {
        return Ok(Exchange::Error {
            message: format!("UnsupportedInSimulator: the engine request exceeds the {} frame limit; it was not sent. Use a smaller message.", frame_size(max)),
            timeout: false,
        });
    }
    frame.0.push(b'\n');
    p.requests.as_ref().ok_or("engine stdin closed")?.try_send((id, frame.0))
        .map_err(|_| "engine stdin unavailable".to_string())?;
    let mut response = p.wait_reply(deadline, stopping, "writing the request or reading the response")?;
    if let Some(err) = response.get("error") {
        let message = err.get("message").and_then(Value::as_str).unwrap_or("engine error").to_string();
        let timeout = err.get("timeout").and_then(Value::as_bool).unwrap_or(false);
        return Ok(Exchange::Error { message, timeout });
    }
    Ok(Exchange::Result(response["result"].take()))
}

/// Only this I/O thread touches pipes. A blocked pipe cannot prevent the owner from terminating the child.
fn pipe_loop(mut stdin: ChildStdin, stdout: ChildStdout, requests: Receiver<(u64, Vec<u8>)>, replies: SyncSender<Reply>, max: usize) {
    let mut stdout = BufReader::new(stdout);
    let ready = match read_frame(&mut stdout, max, |v| v["event"] == "ready") {
        Ok(ready) => ready,
        Err(e) => { let _ = replies.send(Err(e)); return; }
    };
    if replies.send(Ok(ready)).is_err() { return; }
    while let Ok((id, frame)) = requests.recv() {
        let response = stdin.write_all(&frame).and_then(|_| stdin.flush()).map_err(|e| format!("engine write failed: {e}"))
            .and_then(|_| read_frame(&mut stdout, max, |v| v.get("id").and_then(Value::as_u64) == Some(id)));
        let failed = response.is_err();
        if replies.send(response).is_err() || failed { break; }
    }
}

fn frame_size(max: usize) -> String {
    if max.is_multiple_of(1024 * 1024) { format!("{} MiB", max / (1024 * 1024)) } else { format!("{max}-byte") }
}

fn read_frame(input: &mut impl BufRead, max: usize, matches: impl Fn(&Value) -> bool) -> Reply {
    let mut frame = Vec::new();
    loop {
        frame.clear();
        let n = (&mut *input).take(max as u64 + 1).read_until(b'\n', &mut frame).map_err(|e| format!("engine read failed: {e}"))?;
        if n > max { return Err(format!("UnsupportedInSimulator: the engine response exceeds the {} frame limit.", frame_size(max))); }
        if n == 0 || frame.last() != Some(&b'\n') { return Err("engine pipe closed before a complete frame".into()); }
        if let Ok(v) = serde_json::from_slice::<Value>(&frame) {
            if matches(&v) { return Ok(v); }
        }
    }
}

fn start(launch: &Launch, profile: &str, cache_dir: Option<&Path>, deadline: Instant, stopping: &AtomicBool, max: usize) -> Result<Process, String> {
    let jvm = matches!(launch, Launch::Jar { .. });
    let mut cmd = match launch {
        Launch::Native(path) => Command::new(path),
        #[cfg(test)]
        Launch::Fixture(mode) => {
            let mut c = Command::new(std::env::current_exe().unwrap());
            c.args(["--exact", "engine::tests::sidecar_fixture", "--nocapture"]).env("CPIWB_TEST_SIDECAR", mode);
            c
        }
        Launch::Jar { java, jar } => {
            let mut c = Command::new(java);
            c.arg("-XX:+UseSerialGC").arg("-Xmx512m").arg("-Xss4m").arg("-Xshare:auto")
                // JVM log output must never reach stdout (the protocol channel).
                .arg("-Xlog:disable").arg("-Xlog:all=warning:stderr")
                // Class-data archive notes ("Skipping ...: Old class has been linked") are not problems.
                .arg("-Xlog:cds*=off:stderr");
            // AppCDS: reuse the class-data archive of an earlier run, or create it on exit.
            // On Windows the JVM reads these options in the ANSI code page; with characters outside
            // it (user name in the cache path) it cannot create the archive and exits with code 1.
            if let Some(dir) = cache_dir.filter(|d| !cfg!(windows) || d.to_string_lossy().is_ascii()) {
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
    #[cfg(not(test))]
    cmd.args(["serve", "--stdio"]);
    #[cfg(test)]
    if !matches!(launch, Launch::Fixture(_)) { cmd.args(["serve", "--stdio"]); }
    cmd.stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit());
    #[cfg(test)]
    if matches!(launch, Launch::Fixture(_)) { cmd.stderr(Stdio::null()); }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let child = cmd.spawn().map_err(|e| format!("Cannot start engine {launch:?}: {e}"))?;
    let (requests, rx) = mpsc::sync_channel(1);
    let (tx, replies) = mpsc::sync_channel(1);
    let mut process = Process { child, requests: Some(requests), replies, next_id: 0, last_used: Instant::now(), jvm };
    let stdin = process.child.stdin.take().expect("piped stdin");
    let stdout = process.child.stdout.take().expect("piped stdout");
    if let Err(e) = std::thread::Builder::new().name("cpiwb-pipe".into()).spawn(move || pipe_loop(stdin, stdout, rx, tx, max)) {
        process.terminate();
        return Err(format!("Cannot start engine pipe thread: {e}"));
    }
    if let Err(e) = process.wait_reply(deadline, stopping, "waiting for readiness") {
        process.terminate();
        return Err(format!("Engine startup ({profile}): {e}{}", path_hint(launch)));
    }
    Ok(process)
}

/// Java on Windows cannot start from a folder whose path has characters outside the ANSI code page
/// (JDK-8205991, open). Non-ASCII is the cheap check; ä/ö/ü in a Western code page still work.
fn path_hint(launch: &Launch) -> &'static str {
    match launch {
        Launch::Jar { java, jar } if cfg!(windows)
            && !(java.to_string_lossy().is_ascii() && jar.to_string_lossy().is_ascii()) =>
            ". Java cannot start from a folder whose path has characters outside the Windows code page, \
             and no link under %ProgramData% could be made. Reinstall CPI Workbench into a folder with \
             plain ASCII characters, e.g. C:\\CPI Workbench, or use the .msi, which installs under \
             Program Files.",
        _ => "",
    }
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

/// Folders above the executable that may hold a repository build (`engine/.../target`): only for a debug
/// build, which runs from `app/src-tauri/target/debug`. An installed app must not look there: if a bundled
/// engine is missing (e.g. removed by a virus scanner), `C:\engine\...` could be another user's program.
fn repository_dirs(app_dir: &Path) -> Vec<PathBuf> {
    if cfg!(debug_assertions) { app_dir.ancestors().map(Path::to_path_buf).collect() } else { Vec::new() }
}

/// Finds the engine for a profile.
/// Native: CPIWB_ENGINE_DIR, next to the app executable (bundled sidecar), repository build (debug builds).
/// Jar: CPIWB_ENGINE_DIR, bundled resources, repository build (debug builds) — plus a Java runtime.
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
            for dir in repository_dirs(&app_dir) {
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
            for dir in repository_dirs(&app_dir) {
                candidates.push(dir.join("engine").join(format!("groovy-{id}")).join("target").join(&file));
            }
            let jar = candidates.into_iter().find(|p| p.is_file())?;
            java_binary(resource_dir).map(|java| Launch::Jar { java, jar })
        }
    }
}

/// Drops the Windows verbatim prefix (`\\?\C:\…`, `\\?\UNC\server\…`) that Tauri's resource_dir
/// returns. Java cannot open a jar given in that form ("Unable to access jarfile").
pub fn plain_path(path: PathBuf) -> PathBuf {
    let s = path.to_string_lossy();
    if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
        return PathBuf::from(format!(r"\\{rest}"));
    }
    match s.strip_prefix(r"\\?\") {
        Some(rest) if rest.as_bytes().get(1) == Some(&b':') => PathBuf::from(rest),
        _ => path.clone(),
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

    #[test]
    fn plain_paths() {
        let p = |s: &str| plain_path(PathBuf::from(s));
        assert_eq!(p(r"\\?\C:\Users\a\CPI Workbench"), PathBuf::from(r"C:\Users\a\CPI Workbench"));
        assert_eq!(p(r"\\?\UNC\server\share\x"), PathBuf::from(r"\\server\share\x"));
        assert_eq!(p(r"\\?\Volume{1234}\x"), PathBuf::from(r"\\?\Volume{1234}\x"));
        assert_eq!(p("/opt/cpi-workbench"), PathBuf::from("/opt/cpi-workbench"));
    }

    #[test]
    fn sidecar_fixture() {
        let Ok(mode) = std::env::var("CPIWB_TEST_SIDECAR") else { return; };
        let stall = || std::thread::sleep(Duration::from_secs(15));
        if mode == "early-exit" { std::process::exit(23); }
        if mode == "no-ready" { stall(); return; }
        let mut out = std::io::stdout().lock();
        writeln!(out, "{{\"event\":\"ready\"}}").unwrap();
        out.flush().unwrap();
        if mode == "no-read" { stall(); return; }
        for line in std::io::stdin().lock().lines() {
            let msg: Value = serde_json::from_str(&line.unwrap()).unwrap();
            if mode == "partial" {
                write!(out, "{{\"id\":{}", msg["id"]).unwrap();
                out.flush().unwrap();
                stall();
                return;
            }
            let reply = match msg["method"].as_str() {
                Some("fail") => json!({"id": msg["id"], "error": {"message": "refused"}}),
                Some("big") => json!({"id": msg["id"], "result": "x".repeat(4096)}),
                _ => json!({"id": msg["id"], "result": msg["method"]}),
            };
            writeln!(out, "{reply}").unwrap();
            out.flush().unwrap();
        }
    }

    #[test]
    fn stalled_or_exited_sidecars_are_bounded_and_another_profile_still_works() {
        let engines = Engines::default();
        // Leave room for a test executable to start under parallel CI load. Use the short startup
        // budget only for the fixture that deliberately never sends ready.
        let limits = Deadlines { startup: Duration::from_secs(8), exchange: Duration::from_secs(1), ..DEADLINES };
        for (mode, expected, args) in [
            ("no-ready", "timeout waiting for readiness", json!({})),
            ("early-exit", "engine pipe closed", json!({})),
            ("partial", "timeout writing the request or reading the response", json!({})),
            ("no-read", "timeout writing the request or reading the response", json!({"data": "a".repeat(1024 * 1024)})),
        ] {
            let fault_limits = if mode == "no-ready" { Deadlines { startup: Duration::from_millis(800), ..limits } } else { limits };
            let now = Instant::now();
            let e = engines.call_with("faulty", &Launch::Fixture(mode), "ping", args, fault_limits).unwrap_err();
            assert!(e.contains(expected), "{mode}: {e}");
            assert!(now.elapsed() < fault_limits.startup + fault_limits.exchange + Duration::from_secs(2),
                "{mode}: {:?}", now.elapsed());
            assert!(engines.slot("faulty").lock().unwrap().is_none());
            assert_eq!(engines.call_with("other", &Launch::Fixture("echo"), "ping", json!({}), limits).unwrap(), "ping");
        }
        assert_eq!(engines.call_with("faulty", &Launch::Fixture("echo"), "next", json!({}), limits).unwrap(), "next");
        assert!(read_frame(&mut std::io::Cursor::new(b"over the cap\n"), 8, |_| true).unwrap_err().contains("frame limit"));
        engines.shutdown();
    }

    #[test]
    fn the_frame_limit_refuses_a_request_without_stopping_the_worker() {
        let engines = Engines::default();
        let limits = Deadlines { frame: 1024, ..DEADLINES };
        let pid = |e: &Engines| e.slot("framed").lock().unwrap().as_ref().map(|p| p.child.id());
        assert_eq!(engines.call_with("framed", &Launch::Fixture("echo"), "ping", json!({}), limits).unwrap(), "ping");
        let before = pid(&engines).expect("a running worker");
        let e = engines.call_with("framed", &Launch::Fixture("echo"), "ping", json!({ "data": "a".repeat(2048) }), limits).unwrap_err();
        assert!(e.starts_with("UnsupportedInSimulator: the engine request exceeds the 1024-byte frame limit"), "{e}");
        assert_eq!(pid(&engines), Some(before), "the request was not sent; the worker is untouched");
        assert_eq!(engines.call_with("framed", &Launch::Fixture("echo"), "after", json!({}), limits).unwrap(), "after");
        // An oversized response leaves the pipe out of step: that worker is stopped, with the limit's message.
        let e = engines.call_with("framed", &Launch::Fixture("echo"), "big", json!({}), limits).unwrap_err();
        assert!(e.starts_with("UnsupportedInSimulator: the engine response exceeds the 1024-byte frame limit"), "{e}");
        assert!(pid(&engines).is_none());
        assert_eq!(engines.call_with("framed", &Launch::Fixture("echo"), "again", json!({}), limits).unwrap(), "again");
        engines.shutdown();
    }

    #[test]
    fn a_waiting_call_outlives_the_longest_call_ahead() {
        assert!(DEADLINES.queue > DEADLINES.startup + DEADLINES.exchange);
    }

    #[test]
    fn queue_expiry_and_shutdown_do_not_wait_for_a_stuck_pipe() {
        let engines = Arc::new(Engines::default());
        let owner = engines.clone();
        let active = std::thread::spawn(move || owner.call_with("busy", &Launch::Fixture("partial"), "ping", json!({}), DEADLINES));
        let slot = engines.slot("busy");
        let until = Instant::now() + Duration::from_secs(3);
        while slot.try_lock().is_ok() {
            assert!(Instant::now() < until, "fixture did not acquire its slot");
            std::thread::sleep(POLL);
        }
        let limits = Deadlines { queue: Duration::from_millis(80), ..DEADLINES };
        let e = engines.call_with("busy", &Launch::Fixture("echo"), "waiting", json!({}), limits).unwrap_err();
        assert!(e.contains("waiting for a turn"), "{e}");
        assert!(!active.is_finished());
        let now = Instant::now();
        engines.shutdown();
        assert!(now.elapsed() < Duration::from_secs(3));
        assert!(active.join().unwrap().unwrap_err().contains("shutting down"));
        assert!(slot.lock().unwrap().is_none());
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

    #[test]
    fn desktop_groovy_uses_the_selected_project_after_idle_and_exit() {
        if std::env::var_os("CPIWB_REQUIRE_ENGINES").is_none() {
            return;
        }
        let parent = std::env::temp_dir().join(format!("cpiwb-desktop-restart-{}", std::process::id()));
        let engines = Engines::default();
        let make_project = |marker: &str| {
            let path = parent.join(marker);
            std::fs::create_dir_all(path.join("platform")).unwrap();
            std::fs::write(path.join("platform/value-mappings.json"), json!([{
                "sourceAgency": "Shop", "sourceIdentifier": "Customer", "sourceValue": "C-77",
                "targetAgency": "S4", "targetIdentifier": "SoldTo", "targetValue": marker,
            }]).to_string()).unwrap();
            path
        };
        let first = make_project("first-project");
        let second = make_project("second-project");
        let select = |profile: &str, path: &Path| {
            let opened = engines.call_desktop(profile, "project.open", json!({ "dir": path.to_string_lossy() }), None).unwrap();
            opened["root"].as_str().unwrap().to_string()
        };
        for profile in ["groovy-1.x", "groovy-2.x"] {
            assert!(engines.locate(profile).is_some(), "{}", missing_message(profile));
            let root = select("saxon-he-9.9", &first); // projected by the UI's file engine, not by Groovy
            let run = |root: Option<String>, expected: &str| {
                let result = engines.call_desktop(profile, "groovy.run", json!({
                    "script": { "content": "import com.sap.it.api.ITApiFactory\n\
                        import com.sap.it.api.mapping.ValueMappingApi\n\
                        def processData(m) { def vm = ITApiFactory.getService(ValueMappingApi, null); \
                        m.setBody(vm.getMappedValue('Shop','Customer','C-77','S4','SoldTo') ?: 'no-project'); m }" },
                    "input": { "body": "unchanged" },
                }), root).unwrap();
                assert_eq!(result["output"]["body"], expected, "{profile}: {result}");
            };
            run(Some(root.clone()), "first-project");
            engines.stop_idle(Duration::ZERO);
            assert!(engines.slot(profile).lock().unwrap().is_none(), "{profile} was not stopped");
            run(Some(root.clone()), "first-project"); // first request to the replacement worker

            let slot = engines.slot(profile);
            {
                let mut current = slot.lock().unwrap();
                let child = &mut current.as_mut().unwrap().child;
                child.kill().unwrap();
                child.wait().unwrap();
            } // the supervisor still has a handle to the exited worker
            let next = select("saxon-he-9.9", &second);
            run(Some(next.clone()), "second-project");
            run(Some(root), "first-project"); // an already-issued request retains its own project
            run(None, "no-project"); // a scratch call after closing a project cannot see its mock data
        }
        engines.shutdown();
        std::fs::remove_dir_all(&parent).unwrap();
    }

    /// The installed app's path through the shell: every engine over stdio, on a copy of the demo
    /// project in the OS temp folder (Windows paths on Windows). Skipped unless CPIWB_REQUIRE_ENGINES
    /// is set; CI sets it with CPIWB_ENGINE_DIR (native executables, jars) and JAVA_HOME (bundled JRE).
    #[test]
    fn all_engines_run_the_demo_project() {
        if std::env::var_os("CPIWB_REQUIRE_ENGINES").is_none() {
            return;
        }
        let demo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../testdata/regression-project");
        let project = std::env::temp_dir().join(format!("cpiwb-demo-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&project);
        copy_dir(&demo, &project);
        let cases = count_cases(&project.join("tests"));
        assert!(cases > 0);

        let engines = Engines::default();
        let mut passed = std::collections::BTreeSet::new();
        for (name, _, _) in PROFILES {
            assert!(engines.locate(name).is_some(), "{}", missing_message(name));
            let info = engines.call_desktop(name, "engine.info", json!({}), None).unwrap();
            assert_eq!(info["profile"], *name, "{info}");
            let opened = engines.call_desktop(name, "project.open", json!({ "dir": project.to_string_lossy() }), None).unwrap();
            let files = opened["files"].as_array().unwrap();
            assert!(files.iter().any(|f| f["path"] == "tests/groovy-attachment/case.json"),
                "project paths use '/': {opened}");
            let r = engines.call_desktop(name, "tests.run", json!({}),
                Some(opened["root"].as_str().unwrap().to_string())).unwrap();
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
        let w = engines.call_desktop("saxon-he-9.9", "file.write",
            json!({ "path": "tests/new-case/input.xml", "content": "<a/>" }), Some(project.to_string_lossy().into_owned())).unwrap();
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
