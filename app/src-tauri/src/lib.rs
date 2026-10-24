mod engine;

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
    let engines = Arc::new(Engines::default());
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { engines: engines.clone() })
        .setup(move |app| {
            *engines.resource_dir.lock().unwrap() = app.path().resource_dir().ok();
            *engines.cache_dir.lock().unwrap() = app.path().app_cache_dir().ok().map(|d| d.join("cds"));
            // Stop idle JVM engines (Groovy) to give memory back.
            let idle = engines.clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(Duration::from_secs(60));
                idle.stop_idle(engine::IDLE_LIMIT);
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![engine_call, engine_profiles])
        .build(tauri::generate_context!())
        .expect("error while building CPI Workbench");
    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            handle.state::<AppState>().engines.shutdown();
        }
    });
}
