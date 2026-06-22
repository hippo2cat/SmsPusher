pub mod app_state;
pub mod commands;
pub mod event_pump;
pub mod i18n;
pub mod keyring_secret_store;
pub mod logging;
pub mod notifications;
pub mod platform_notifications;
pub mod storage;
pub mod tray_presentation;
pub mod updates;

use app_state::SmsPusherAppState;
use commands::{
    event_name, get_settings, get_status, hide_tray_popover, list_devices, list_messages,
    list_network_interfaces, open_history_from_tray, quit_app, refresh_pairing_code, retry_queue,
    revoke_device, test_transport, update_settings,
};
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, LogicalPosition, Manager, PhysicalPosition, Position, Rect, Size,
    WebviewUrl, WebviewWindowBuilder, WindowEvent, Wry,
};

const TRAY_ID: &str = "sms-menu-bar";
const TRAY_POPOVER_LABEL: &str = "tray";
const TRAY_POPOVER_WIDTH: i32 = 376;
const TRAY_POPOVER_HEIGHT: i32 = 530;
const TRAY_POPOVER_MIN_HEIGHT: i32 = 1;
const TRAY_POPOVER_MARGIN: i32 = 10;
const MENU_REFRESH_PAIRING_ID: &str = "refresh_pairing_code";
const MENU_OPEN_HISTORY_ID: &str = "open_history";
const MENU_SHOW_POPOVER_ID: &str = "show_tray_popover";
const MENU_QUIT_ID: &str = "quit";
const TRAY_ICON: tauri::image::Image<'static> =
    tauri::include_image!("./icons/tray-icon-white-bg-circle.png");

fn app_data_dir(app: &tauri::App) -> std::path::PathBuf {
    let tauri_data_dir = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| std::env::temp_dir().join(storage::SUPPORT_DIRECTORY_NAME));
    storage::resolve_sms_pusher_data_dir(tauri_data_dir)
}

fn emit_pending_events(app: &AppHandle<Wry>, state: &SmsPusherAppState) {
    for event in state.drain_events() {
        let name = event_name(&event);
        if let Err(error) = app.emit(name, event) {
            tracing::warn!(event = name, error = %error, "failed to emit service event");
        }
    }
}

fn build_tray_menu(app: &AppHandle<Wry>) -> tauri::Result<Menu<Wry>> {
    let show_popover_item = MenuItem::with_id(
        app,
        MENU_SHOW_POPOVER_ID,
        "Open SmsPusher",
        true,
        None::<&str>,
    )?;
    let refresh_pairing_item = MenuItem::with_id(
        app,
        MENU_REFRESH_PAIRING_ID,
        "Refresh Pairing Code",
        true,
        Some("CmdOrCtrl+P"),
    )?;
    let open_history_item = MenuItem::with_id(
        app,
        MENU_OPEN_HISTORY_ID,
        "Open History",
        true,
        Some("CmdOrCtrl+H"),
    )?;
    let quit_item = MenuItem::with_id(app, MENU_QUIT_ID, "Quit", true, Some("CmdOrCtrl+Q"))?;
    let first_separator = PredefinedMenuItem::separator(app)?;
    let second_separator = PredefinedMenuItem::separator(app)?;

    let menu = Menu::with_items(
        app,
        &[
            &show_popover_item,
            &refresh_pairing_item,
            &first_separator,
            &open_history_item,
            &second_separator,
            &quit_item,
        ],
    )?;
    Ok(menu)
}

fn tray_popover_position(rect: Rect) -> Position {
    match (rect.position, rect.size) {
        (Position::Physical(position), Size::Physical(size)) => {
            let x = position.x + (size.width as i32 - TRAY_POPOVER_WIDTH) / 2;
            let y = position.y + size.height as i32 + TRAY_POPOVER_MARGIN;
            Position::Physical(PhysicalPosition::new(x.max(TRAY_POPOVER_MARGIN), y))
        }
        (Position::Logical(position), Size::Logical(size)) => {
            let x = position.x + (size.width - TRAY_POPOVER_WIDTH as f64) / 2.0;
            let y = position.y + size.height + TRAY_POPOVER_MARGIN as f64;
            Position::Logical(LogicalPosition::new(x.max(TRAY_POPOVER_MARGIN as f64), y))
        }
        (position, size) => {
            let position = position.to_physical::<i32>(1.0);
            let size = size.to_physical::<u32>(1.0);
            let x = position.x + (size.width as i32 - TRAY_POPOVER_WIDTH) / 2;
            let y = position.y + size.height as i32 + TRAY_POPOVER_MARGIN;
            Position::Physical(PhysicalPosition::new(x.max(TRAY_POPOVER_MARGIN), y))
        }
    }
}

fn ensure_tray_popover(app: &AppHandle<Wry>) -> tauri::Result<tauri::WebviewWindow<Wry>> {
    if let Some(window) = app.get_webview_window(TRAY_POPOVER_LABEL) {
        return Ok(window);
    }

    WebviewWindowBuilder::new(
        app,
        TRAY_POPOVER_LABEL,
        WebviewUrl::App("index.html?view=tray".into()),
    )
    .title("SmsPusher")
    .inner_size(TRAY_POPOVER_WIDTH as f64, TRAY_POPOVER_HEIGHT as f64)
    .min_inner_size(TRAY_POPOVER_WIDTH as f64, TRAY_POPOVER_MIN_HEIGHT as f64)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .shadow(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .visible(false)
    .focused(true)
    .build()
}

fn toggle_tray_popover(app: &AppHandle<Wry>, rect: Rect) -> tauri::Result<()> {
    let window = ensure_tray_popover(app)?;
    if window.is_visible()? {
        window.hide()?;
        return Ok(());
    }
    window.set_position(tray_popover_position(rect))?;
    window.show()?;
    window.set_focus()?;
    app.emit_to(TRAY_POPOVER_LABEL, "tray_popover_opened", ())?;
    Ok(())
}

fn handle_tray_icon_event(app: &AppHandle<Wry>, event: TrayIconEvent) {
    if let TrayIconEvent::Click {
        button: MouseButton::Left,
        button_state: MouseButtonState::Down,
        rect,
        ..
    } = event
    {
        tracing::info!("tray icon left click");
        if let Err(error) = toggle_tray_popover(app, rect) {
            tracing::warn!(error = %error, "failed to toggle tray popover");
        }
    }
}

fn start_tray_refresh_loop(app: AppHandle<Wry>) {
    tauri::async_runtime::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(1));
        loop {
            interval.tick().await;
            let state = app.state::<SmsPusherAppState>();
            match state.get_status() {
                Ok(status) if status.pairing_code.expires_at <= chrono::Utc::now() => {
                    if let Err(error) = state.refresh_pairing_code() {
                        tracing::warn!(error = %error, "failed to refresh expired pairing code");
                    }
                    emit_pending_events(&app, &state);
                }
                Ok(_) => {}
                Err(error) => tracing::warn!(error = %error, "failed to read status for tray refresh"),
            }
        }
    });
}

fn configure_tray(app: &tauri::App) -> tauri::Result<()> {
    let handle = app.handle().clone();
    let menu = build_tray_menu(&handle)?;
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(TRAY_ICON.clone())
        .icon_as_template(false)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| handle_tray_icon_event(tray.app_handle(), event))
        .on_menu_event(|app, event| match event.id.as_ref() {
            MENU_SHOW_POPOVER_ID => {
                if let Some(tray) = app.tray_by_id(TRAY_ID) {
                    match tray.rect() {
                        Ok(Some(rect)) => {
                            tracing::info!("tray menu show popover");
                            if let Err(error) = toggle_tray_popover(app, rect) {
                                tracing::warn!(error = %error, "failed to show tray popover");
                            }
                        }
                        Ok(None) => tracing::warn!("failed to show tray popover: missing tray rect"),
                        Err(error) => tracing::warn!(error = %error, "failed to read tray rect"),
                    }
                }
            }
            MENU_OPEN_HISTORY_ID => {
                tracing::info!("tray menu open history");
                if let Err(error) = commands::open_history_from_app(app) {
                    tracing::warn!(error = %error, "failed to open history window");
                }
            }
            MENU_REFRESH_PAIRING_ID => {
                tracing::info!("tray menu refresh pairing code");
                let state = app.state::<SmsPusherAppState>();
                if let Err(error) = state.refresh_pairing_code() {
                    tracing::warn!(error = %error, "failed to refresh pairing code");
                }
                emit_pending_events(app, &state);
            }
            MENU_QUIT_ID => {
                tracing::info!("tray menu quit");
                app.exit(0)
            }
            _ => {}
        })
        .build(app)?;
    start_tray_refresh_loop(handle);
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory);
            platform_notifications::request_system_notification_authorization();

            let data_dir = app_data_dir(app);
            let logging_guard = logging::init_file_logging(&data_dir);
            app.manage(logging_guard);
            tracing::info!(data_dir = %data_dir.display(), "desktop app setup started");
            storage::prepare_sms_pusher_data_dir(&data_dir)?;
            updates::start_macos_update_check(data_dir.clone());
            let state = SmsPusherAppState::new_for_data_dir(data_dir)?;
            if state.settings().lan_enabled {
                tauri::async_runtime::block_on(state.start_lan_server())?;
            }
            app.manage(state);
            configure_tray(app)?;
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(event_pump::run_event_pump(handle));
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == TRAY_POPOVER_LABEL {
                if let WindowEvent::Focused(false) = event {
                    let _ = window.hide();
                    return;
                }
            }
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_status,
            get_settings,
            hide_tray_popover,
            list_devices,
            list_messages,
            list_network_interfaces,
            open_history_from_tray,
            quit_app,
            refresh_pairing_code,
            revoke_device,
            retry_queue,
            test_transport,
            update_settings
        ])
        .run(tauri::generate_context!())
        .expect("error while running SmsPusher Tauri application");
}
