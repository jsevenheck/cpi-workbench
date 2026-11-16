#[cfg(windows)]
mod ascii_alias;
mod engine;
mod mcp;
mod receiver;

use engine::Engines;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, RunEvent, State};

struct AppState {
    engines: Arc<Engines>,
    receiver: Arc<receiver::Receiver>,
}

/// Forwards one RPC call to the engine for `profile`.
#[tauri::command]
async fn engine_call(state: State<'_, AppState>, profile: String, method: String, params: Value) -> Result<Value, String> {
    let engines = state.engines.clone();
    tauri::async_runtime::spawn_blocking(move || engines.call(&profile, &method, params))
        .await
        .map_err(|e| e.to_string())?
}

/// Profiles whose engine is installed.
#[tauri::command]
async fn engine_profiles(state: State<'_, AppState>) -> Result<Vec<String>, String> {
    let engines = state.engines.clone();
    tauri::async_runtime::spawn_blocking(move || engines.available())
        .await
        .map_err(|e| e.to_string())
}

const RELEASES_PAGE: &str = "https://github.com/jsevenheck/cpi-workbench/releases/";

/// A release page of this repository, in characters no shell or opener treats specially.
fn is_release_page(url: &str) -> bool {
    url.strip_prefix(RELEASES_PAGE)
        .is_some_and(|rest| rest.chars().all(|c| c.is_ascii_alphanumeric() || "./_-".contains(c)) && !rest.contains(".."))
}

/// Opens a release page in the default browser (*Check for updates*). Only this repository's release
/// pages, so the webview cannot open anything else; the system's own opener, no extra crate.
#[tauri::command]
fn open_release_page(url: String) -> Result<(), String> {
    if !is_release_page(&url) {
        return Err(format!("Not a release page of CPI Workbench: {url}"));
    }
    let mut cmd = if cfg!(windows) {
        let mut c = std::process::Command::new("rundll32");
        c.args(["url.dll,FileProtocolHandler", &url]);
        c
    } else if cfg!(target_os = "macos") {
        let mut c = std::process::Command::new("open");
        c.arg(&url);
        c
    } else {
        let mut c = std::process::Command::new("xdg-open");
        c.arg(&url);
        c
    };
    let mut child = cmd.spawn().map_err(|e| format!("Could not open the browser: {e}"))?;
    std::thread::spawn(move || child.wait());
    Ok(())
}

/// Turns the receiver for CPI Helper's links on at `port`, or off (docs/plan.md W2b). A link that arrives
/// goes to the UI, which asks before it imports it, and brings the window to the front. Off at once, on a
/// worker thread: stopping waits for the port to be free.
#[tauri::command]
async fn receiver_set(app: AppHandle, state: State<'_, AppState>, port: Option<u16>) -> Result<(), String> {
    let receiver = state.receiver.clone();
    let deliver: receiver::Deliver = Arc::new(move |data| {
        let _ = app.emit(receiver::EVENT, data);
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.unminimize();
            let _ = window.set_focus();
        }
    });
    tauri::async_runtime::spawn_blocking(move || receiver.set(port, deliver))
        .await
        .map_err(|e| e.to_string())?
}

pub fn run() {
    let context = tauri::generate_context!();
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("mcp") {
        std::process::exit(run_mcp(&args[1..], &context));
    }
    let engines = Arc::new(Engines::default());
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { engines: engines.clone(), receiver: Default::default() })
        .setup(move |app| {
            let resources = app.path().resource_dir().ok().map(engine::plain_path);
            let cache = app.path().app_cache_dir().ok().map(|d| d.join("cds"));
            #[cfg(windows)]
            let (resources, cache) = ascii_alias::for_java(resources, cache);
            *engines.resource_dir.lock().unwrap() = resources;
            *engines.cache_dir.lock().unwrap() = cache;
            // Stop idle JVM engines (Groovy) to give memory back.
            let idle = engines.clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(Duration::from_secs(60));
                idle.stop_idle(engine::IDLE_LIMIT);
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![engine_call, engine_profiles, open_release_page, receiver_set])
        .build(context)
        .expect("error while building CPI Workbench");
    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            handle.state::<AppState>().engines.shutdown();
        }
    });
}

/// `cpi-workbench mcp`: the MCP server over stdio, with the installed app's engines (docs/mcp.md).
fn run_mcp(args: &[String], context: &tauri::Context<tauri::Wry>) -> i32 {
    let resources = tauri::utils::platform::resource_dir(context.package_info(), &tauri::Env::default())
        .ok().map(engine::plain_path);
    // Its own class-data archives, so the app and the server never write the same file.
    let cache = user_cache_dir().map(|d| d.join(&context.config().identifier).join("cds-mcp"));
    #[cfg(windows)]
    let (resources, cache) = ascii_alias::for_java(resources, cache);
    mcp::main(args, resources, cache)
}

/// The per-user cache folder that Tauri's app_cache_dir() is based on.
fn user_cache_dir() -> Option<std::path::PathBuf> {
    let env = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty()).map(std::path::PathBuf::from);
    if cfg!(windows) {
        env("LOCALAPPDATA")
    } else if cfg!(target_os = "macos") {
        env("HOME").map(|h| h.join("Library").join("Caches"))
    } else {
        env("XDG_CACHE_HOME").filter(|p| p.is_absolute()).or_else(|| env("HOME").map(|h| h.join(".cache")))
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn opens_release_pages_only() {
        assert!(super::is_release_page("https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.3.0"));
        assert!(super::is_release_page("https://github.com/jsevenheck/cpi-workbench/releases/"));
        for bad in ["https://github.com/jsevenheck/cpi-workbench/releases/tag/v1 & calc", "https://example.com/",
                    "https://github.com/jsevenheck/cpi-workbench/releases/../../other", "file:///etc/passwd",
                    "https://github.com/jsevenheck/cpi-workbench/releases/tag/\"x\""] {
            assert!(!super::is_release_page(bad), "{bad}");
        }
    }
}
