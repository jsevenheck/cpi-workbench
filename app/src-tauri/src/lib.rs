#[cfg(windows)]
mod ascii_alias;
mod engine;
mod mcp;

use engine::Engines;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use tauri::{Manager, RunEvent, State};

struct AppState {
    engines: Arc<Engines>,
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

pub fn run() {
    let context = tauri::generate_context!();
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) == Some("mcp") {
        std::process::exit(run_mcp(&args[1..], &context));
    }
    let engines = Arc::new(Engines::default());
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { engines: engines.clone() })
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
        .invoke_handler(tauri::generate_handler![engine_call, engine_profiles])
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
