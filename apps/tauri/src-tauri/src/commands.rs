use crate::app_state::{AppSettingsSnapshot, AppSettingsUpdate, QueueSnapshot, SmsPusherAppState};
use serde::Serialize;
use smspusher_service::{
    DeviceSnapshot, MessageSnapshot, ServiceEvent, StatusSnapshot, TransportSnapshot,
};
use tauri::{AppHandle, Emitter, Manager, State};

pub const COMMAND_NAMES: [&str; 13] = [
    "get_settings",
    "get_status",
    "hide_tray_popover",
    "list_devices",
    "list_messages",
    "list_network_interfaces",
    "open_history_from_tray",
    "quit_app",
    "refresh_pairing_code",
    "revoke_device",
    "retry_queue",
    "test_transport",
    "update_settings",
];

pub const EVENT_NAMES: [&str; 7] = [
    "status_changed",
    "pairing_code_changed",
    "device_changed",
    "message_received",
    "queue_changed",
    "transport_changed",
    "notification_action",
];

pub fn event_name(event: &ServiceEvent) -> &'static str {
    match event {
        ServiceEvent::StatusChanged => "status_changed",
        ServiceEvent::PairingCodeChanged { .. } => "pairing_code_changed",
        ServiceEvent::DeviceChanged { .. } => "device_changed",
        ServiceEvent::MessageReceived { .. } => "message_received",
        ServiceEvent::QueueChanged { .. } => "queue_changed",
        ServiceEvent::TransportChanged { .. } => "transport_changed",
        ServiceEvent::NotificationAction { .. } => "notification_action",
    }
}

fn emit_events(app: &AppHandle, state: &SmsPusherAppState) -> Result<(), String> {
    for event in state.drain_events() {
        let name = event_name(&event);
        app.emit(name, event)
            .map_err(|error| error.to_string())?;
        tracing::debug!(event = name, "service event emitted from command");
    }
    Ok(())
}

pub fn open_history_from_app(app: &AppHandle) -> Result<(), String> {
    tracing::info!("opening history window");
    if let Some(tray_window) = app.get_webview_window("tray") {
        tray_window.hide().map_err(|error| error.to_string())?;
    }
    if let Some(window) = app.get_webview_window("main") {
        window.show().map_err(|error| error.to_string())?;
        window.set_focus().map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NetworkInterfaceSnapshot {
    pub id: String,
    pub name: String,
    pub ipv4: String,
    pub label: String,
}

#[tauri::command]
pub fn get_status(state: State<'_, SmsPusherAppState>) -> Result<StatusSnapshot, String> {
    state.get_status().map_err(|error| error.to_string())
}

#[tauri::command]
pub fn get_settings(state: State<'_, SmsPusherAppState>) -> AppSettingsSnapshot {
    state.settings()
}

#[tauri::command]
pub fn hide_tray_popover(app: AppHandle) -> Result<(), String> {
    tracing::info!("hide tray popover command");
    if let Some(window) = app.get_webview_window("tray") {
        window.hide().map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub fn list_devices(state: State<'_, SmsPusherAppState>) -> Result<Vec<DeviceSnapshot>, String> {
    state.list_devices().map_err(|error| error.to_string())
}

#[tauri::command]
pub fn list_messages(state: State<'_, SmsPusherAppState>) -> Result<Vec<MessageSnapshot>, String> {
    state.list_messages().map_err(|error| error.to_string())
}

#[tauri::command]
pub fn list_network_interfaces(
    state: State<'_, SmsPusherAppState>,
) -> Vec<NetworkInterfaceSnapshot> {
    state
        .network_interfaces()
        .into_iter()
        .map(|interface| {
            let label = interface.label();
            let ipv4 = interface.ipv4.to_string();
            NetworkInterfaceSnapshot {
                id: interface.id,
                name: interface.name,
                ipv4,
                label,
            }
        })
        .collect()
}

#[tauri::command]
pub fn open_history_from_tray(app: AppHandle) -> Result<(), String> {
    open_history_from_app(&app)
}

#[tauri::command]
pub fn quit_app(app: AppHandle) {
    app.exit(0);
}

#[tauri::command]
pub fn refresh_pairing_code(
    app: AppHandle,
    state: State<'_, SmsPusherAppState>,
) -> Result<String, String> {
    tracing::info!("refresh pairing code command");
    let value = state
        .refresh_pairing_code()
        .map_err(|error| error.to_string())?;
    emit_events(&app, &state)?;
    Ok(value)
}

#[tauri::command]
pub fn revoke_device(
    app: AppHandle,
    state: State<'_, SmsPusherAppState>,
    device_id: String,
) -> Result<StatusSnapshot, String> {
    tracing::info!(device_id = %device_id, "revoke device command");
    let status = state
        .revoke_device(&device_id)
        .map_err(|error| error.to_string())?;
    emit_events(&app, &state)?;
    Ok(status)
}

#[tauri::command]
pub fn retry_queue(state: State<'_, SmsPusherAppState>) -> QueueSnapshot {
    tracing::info!("retry queue command");
    state.retry_queue()
}

#[tauri::command]
pub fn test_transport(state: State<'_, SmsPusherAppState>) -> Result<TransportSnapshot, String> {
    tracing::info!("test transport command");
    state.test_transport().map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn update_settings(
    app: AppHandle,
    state: State<'_, SmsPusherAppState>,
    update: AppSettingsUpdate,
) -> Result<AppSettingsSnapshot, String> {
    tracing::info!(update = ?update, "update settings command");
    let before = state.settings();
    let settings = state
        .update_settings(update)
        .map_err(|error| error.to_string())?;
    state
        .apply_lan_settings(before)
        .await
        .map_err(|error| error.to_string())?;
    emit_events(&app, &state)?;
    Ok(settings)
}
