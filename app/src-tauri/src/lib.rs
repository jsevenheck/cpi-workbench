mod engine;

use engine::Engines;
use serde_json::Value;
use std::sync::Arc;
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
fn engine_profiles() -> Vec<String> {
    engine::available()
}

pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { engines: Arc::new(Engines::default()) })
        .invoke_handler(tauri::generate_handler![engine_call, engine_profiles])
        .build(tauri::generate_context!())
        .expect("error while building CPI Workbench");
    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            handle.state::<AppState>().engines.shutdown();
        }
    });
}
