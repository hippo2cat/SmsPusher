use serde_json::Value;
use smspusher_tauri_lib::commands::{COMMAND_NAMES, EVENT_NAMES};
use std::{fs::File, io::BufReader, path::Path};

#[test]
fn command_names_match_desktop_spec() {
    assert_eq!(
        COMMAND_NAMES.as_slice(),
        [
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
        ]
        .as_slice()
    );
}

#[test]
fn event_names_match_desktop_spec() {
    assert_eq!(
        EVENT_NAMES,
        [
            "status_changed",
            "pairing_code_changed",
            "device_changed",
            "message_received",
            "queue_changed",
            "transport_changed",
            "notification_action",
        ]
    );
}

#[test]
fn lifecycle_keeps_command_surface_stable() {
    assert!(!COMMAND_NAMES.contains(&"start_lan_server"));
    assert!(!COMMAND_NAMES.contains(&"stop_lan_server"));
}

#[test]
fn main_window_starts_hidden_for_menu_bar_lifecycle() {
    let config: Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
    let main_window = config["app"]["windows"]
        .as_array()
        .unwrap()
        .iter()
        .find(|window| window["label"] == "main")
        .expect("main window config");

    assert_eq!(main_window["visible"], false);
}

#[test]
fn tray_menu_opens_history_instead_of_generic_show() {
    let source = include_str!("../src/lib.rs");

    assert!(source.contains("\"Open History\""));
    assert!(!source.contains("\"Show\""));
}

#[test]
fn tray_menu_matches_swift_menu_bar_contract() {
    let source = include_str!("../src/lib.rs");

    assert!(source.contains("Refresh Pairing Code"));
    assert!(source.contains("show_menu_on_left_click(false)"));
    assert!(!source.contains("show_menu_on_right_click"));
}

#[test]
fn network_interface_menu_selection_restarts_lan_publishing() {
    let source = std::fs::read_to_string(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../src/TrayPopover.tsx"),
    )
    .unwrap_or_default();
    let app_state = include_str!("../src/app_state.rs");

    assert!(source.contains("listNetworkInterfaces"));
    assert!(source.contains("updateSettings"));
    assert!(source.contains("networkInterfaceId"));
    assert!(app_state.contains("before.network_interface_id != after.network_interface_id"));
}

#[test]
fn tray_refresh_loop_does_not_replace_open_menu_every_tick() {
    let source = include_str!("../src/lib.rs");
    let refresh_loop = source
        .split("fn start_tray_refresh_loop")
        .nth(1)
        .and_then(|tail| tail.split("fn configure_tray").next())
        .expect("refresh loop body");

    assert!(!refresh_loop.contains("failed to sync tray menu: {error}"));
    assert!(!refresh_loop.contains("sync_tray_menu(&app)"));
    assert!(!refresh_loop.contains("set_title"));
    assert!(!refresh_loop.contains("set_text"));
}

#[test]
fn tray_left_click_opens_react_popover_instead_of_native_menu() {
    let source = include_str!("../src/lib.rs");

    assert!(source.contains("TRAY_POPOVER_LABEL"));
    assert!(source.contains("TRAY_POPOVER_WIDTH: i32 = 376"));
    assert!(source.contains("TRAY_POPOVER_HEIGHT: i32 = 530"));
    assert!(source.contains("(size.width as i32 - TRAY_POPOVER_WIDTH) / 2"));
    assert!(source.contains("WebviewWindowBuilder"));
    assert!(source.contains("WebviewUrl::App(\"index.html?view=tray\".into())"));
    assert!(source.contains("toggle_tray_popover"));
    assert!(source.contains(".on_tray_icon_event"));
    assert!(source.contains("show_menu_on_left_click(false)"));
    assert!(source.contains("transparent(true)"));
    assert!(source.contains("shadow(false)"));
    assert!(source.contains("WindowEvent::Focused(false)"));
}

#[test]
fn tray_popover_frontend_uses_react_vite_and_native_countdown_animation() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let package = std::fs::read_to_string(tauri_dir.join("package.json")).unwrap_or_default();
    let app = std::fs::read_to_string(tauri_dir.join("src/App.tsx")).unwrap_or_default();
    let tray = std::fs::read_to_string(tauri_dir.join("src/TrayPopover.tsx")).unwrap_or_default();
    let styles = std::fs::read_to_string(tauri_dir.join("src/styles.css")).unwrap_or_default();

    assert!(package.contains("\"vite\""));
    assert!(package.contains("\"react\""));
    assert!(!package.contains("\"gsap\""));
    assert!(!package.contains("\"@gsap/react\""));
    assert!(app.contains(".get(\"view\")"));
    assert!(app.contains("view === \"tray\""));
    assert!(app.contains("TrayPopover"));
    assert!(!tray.contains("useGSAP"));
    assert!(tray.contains(".animate("));
    assert!(tray.contains("setInterval"));
    assert!(tray.contains("pairingCode.expiresAt"));
    assert!(tray.contains("revokeDevice"));
    assert!(tray.contains("Link2Off"));
    assert!(tray.contains("hideTrayPopover"));
    assert!(tray.contains("openHistoryFromTray"));
    assert!(tray.contains("t(\"tray.pairingCode.validFor\")"));
    assert!(tray.contains("t(\"tray.devices.title\")"));
    assert!(tray.contains("t(\"tray.history\")"));
    assert!(tray.contains("t(\"common.exit\")"));
    assert!(tray.contains("changeAppLanguage"));
    assert!(styles.contains("@keyframes popup-card-enter"));
    assert!(styles.contains("@keyframes popup-item-enter"));
}

#[test]
fn tray_popover_exposes_language_selector_and_uses_translation_keys() {
    let tray = std::fs::read_to_string("../src/TrayPopover.tsx").expect("tray source");

    assert!(tray.contains("const { t, i18n } = useTranslation()"));
    assert!(tray.contains("languagePreference"));
    assert!(tray.contains("chooseLanguage"));
    assert!(tray.contains("t(\"tray.pairingCode.title\")"));
    assert!(tray.contains("t(\"common.language.title\")"));
    assert!(!tray.contains(">配对码<"));
    assert!(!tray.contains(">已配对设备<"));
    assert!(!tray.contains(">退出<"));
}

#[test]
fn tauri_frontend_uses_i18next_for_user_visible_copy() {
    let package_json = include_str!("../../package.json");
    let i18n = std::fs::read_to_string("../src/i18n/index.ts").expect("i18n init source");
    let main = std::fs::read_to_string("../src/main.tsx").expect("main source");

    assert!(package_json.contains("\"i18next\""));
    assert!(package_json.contains("\"react-i18next\""));
    assert!(i18n.contains("initReactI18next"));
    assert!(i18n.contains("resolveLocale"));
    assert!(i18n.contains("keySeparator: false"));
    assert!(main.contains("import \"./i18n\";"));
}

#[test]
fn history_window_uses_translation_keys_for_user_visible_copy() {
    let history = std::fs::read_to_string("../src/HistoryApp.tsx").expect("history source");

    assert!(history.contains("useTranslation"));
    assert!(history.contains("t(\"history.title\")"));
    assert!(history.contains("t(\"history.searchPlaceholder\")"));
    assert!(history.contains("t(\"history.copyCode\")"));
    assert!(!history.contains("SMS History"));
    assert!(!history.contains("Copy Code"));
    assert!(!history.contains("No messages yet"));
}

#[test]
fn tauri_frontend_build_targets_webview_compatible_js() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let vite = std::fs::read_to_string(tauri_dir.join("vite.config.ts")).unwrap_or_default();

    assert!(vite.contains("target: \"safari13\""));
    assert!(vite.contains("cssTarget: \"safari13\""));
    assert!(vite.contains("esbuild: {\n    target: \"safari13\""));
}

#[test]
fn tray_popover_expands_to_content_instead_of_scrolling_settings_drawer() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let lib = std::fs::read_to_string(tauri_dir.join("src-tauri/src/lib.rs")).unwrap_or_default();
    let capabilities =
        std::fs::read_to_string(tauri_dir.join("src-tauri/capabilities/default.json"))
            .unwrap_or_default();
    let tray = std::fs::read_to_string(tauri_dir.join("src/TrayPopover.tsx")).unwrap_or_default();
    let styles = std::fs::read_to_string(tauri_dir.join("src/styles.css")).unwrap_or_default();

    assert!(lib.contains("TRAY_POPOVER_MIN_HEIGHT: i32 = 1"));
    assert!(lib.contains(".min_inner_size(TRAY_POPOVER_WIDTH as f64, TRAY_POPOVER_MIN_HEIGHT as f64)"));
    assert!(tray.contains("getCurrentWindow"));
    assert!(tray.contains("new LogicalSize"));
    assert!(tray.contains("setSize"));
    assert!(capabilities.contains("\"core:window:allow-set-size\""));
    assert!(tray.contains("className=\"popup-scroll\""));
    assert!(
        tray.find("className=\"popup-scroll\"").unwrap()
            < tray.find("className=\"popup-footer").unwrap()
    );
    assert!(!styles.contains("height: 454px;"));
    assert!(!styles.contains("height: 530px;"));
    assert!(!styles.contains("max-height: calc(100% - 48px);"));
    assert!(!styles.contains("overflow-y: auto;"));
    assert!(styles.contains("html,\nbody,\n#root {\n  width: 100%;\n  height: 100%;"));
    assert!(styles.contains("html,\nbody,\n#root {\n  width: 100%;\n  height: 100%;\n  margin: 0;\n  overflow: hidden;"));
    assert!(styles.contains(".popup-card {\n  position: relative;\n  width: 300px;\n  display: flex;\n  flex-direction: column;"));
    assert!(styles.contains(".popup-scroll {\n  flex: 0 0 auto;\n  overflow: visible;"));
    assert!(styles.contains(".settings-drawer {\n  height: 0;\n  overflow: hidden;"));
    assert!(styles.contains(".popup-footer {\n  flex: 0 0 48px;"));
    assert!(!styles.contains(".popup-footer {\n  position: absolute;"));
}

#[test]
fn desktop_logging_uses_tracing_file_appender() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let cargo = std::fs::read_to_string(tauri_dir.join("src-tauri/Cargo.toml")).unwrap_or_default();
    let lib = std::fs::read_to_string(tauri_dir.join("src-tauri/src/lib.rs")).unwrap_or_default();
    let event_pump =
        std::fs::read_to_string(tauri_dir.join("src-tauri/src/event_pump.rs")).unwrap_or_default();
    let logging =
        std::fs::read_to_string(tauri_dir.join("src-tauri/src/logging.rs")).unwrap_or_default();

    assert!(cargo.contains("tracing ="));
    assert!(cargo.contains("tracing-appender ="));
    assert!(cargo.contains("tracing-subscriber ="));
    assert!(lib.contains("pub mod logging;"));
    assert!(lib.contains("logging::init_file_logging(&data_dir)"));
    assert!(lib.contains("app.manage(logging_guard);"));
    assert!(logging.contains("tracing_appender::rolling"));
    assert!(logging.contains("tracing_subscriber"));
    assert!(logging.contains("recent_log_text"));
    assert!(!lib.contains("eprintln!"));
    assert!(!event_pump.contains("eprintln!"));
}

#[test]
fn history_window_static_ui_matches_swift_history_contract() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let html = std::fs::read_to_string(tauri_dir.join("dist/index.html")).unwrap_or_default();
    let history = std::fs::read_to_string(tauri_dir.join("src/HistoryApp.tsx")).unwrap_or_default();
    let tauri_api = std::fs::read_to_string(tauri_dir.join("src/tauri.ts")).unwrap_or_default();

    assert!(html.contains("<title>SmsPusher</title>"));
    assert!(html.contains("/main.js"));
    assert!(html.contains("/styles.css"));
    assert!(history.contains("t(\"history.title\")"));
    assert!(history.contains("t(\"history.searchPlaceholder\")"));
    assert!(history.contains("t(\"history.refresh\")"));
    assert!(history.contains("t(\"history.sender\")"));
    assert!(history.contains("t(\"history.time\")"));
    assert!(history.contains("t(\"history.code\")"));
    assert!(history.contains("t(\"history.message\")"));
    assert!(history.contains("t(\"history.device\")"));
    assert!(history.contains("t(\"history.copyCode\")"));
    assert!(history.contains("t(\"history.copyBody\")"));
    assert!(history.contains("t(\"history.copySender\")"));
    assert!(history.contains("t(\"history.noMessages\")"));
    assert!(history.contains("t(\"history.noMatches\")"));
    assert!(history.contains("t(\"history.emptyDetail\")"));
    assert!(history.contains("t(\"history.tryDifferentSearch\")"));
    assert!(history.contains("t(\"history.copied\""));
    assert!(history.contains("t(\"history.count\""));
    assert!(tauri_api.contains("list_messages"));
    assert!(tauri_api.contains("message_received"));
    assert!(history.contains("navigator.clipboard"));
    assert!(history.contains("getSettings"));
    assert!(history.contains("changeAppLanguage"));
    assert!(!history.contains("<h2>Pairing</h2>"));
    assert!(!history.contains("<h2>Devices</h2>"));
    assert!(!history.contains("<h2>Settings</h2>"));
}

#[test]
fn macos_runtime_uses_accessory_activation_policy() {
    let source = include_str!("../src/lib.rs");

    assert!(source.contains("tauri::ActivationPolicy::Accessory"));
    assert!(source.contains("set_activation_policy"));
}

#[test]
fn tray_uses_brand_icon_instead_of_text_status_item() {
    let source = include_str!("../src/lib.rs");

    assert!(source.contains("TRAY_ICON: tauri::image::Image<'static>"));
    assert!(source.contains("tauri::include_image!(\"./icons/tray-icon.png\")"));
    assert!(source.contains(".icon(TRAY_ICON.clone())"));
    assert!(source.contains(".icon_as_template(true)"));
    assert!(!source.contains(".title(&title)"));
    assert!(!source.contains("current_tray_title"));
}

#[test]
fn tray_icon_png_has_transparent_background_for_macos_template_rendering() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let tray_icon = manifest_dir.join("icons/tray-icon.png");
    let file = File::open(&tray_icon).expect("tray icon png");
    let decoder = png::Decoder::new(BufReader::new(file));
    let mut reader = decoder.read_info().expect("png metadata");
    let mut buffer = vec![0; reader.output_buffer_size().expect("png output buffer size")];
    let frame = reader.next_frame(&mut buffer).expect("png frame");
    let bytes = &buffer[..frame.buffer_size()];

    assert_eq!((frame.width, frame.height), (64, 64));
    assert_eq!(frame.color_type, png::ColorType::Rgba);
    assert_eq!(frame.bit_depth, png::BitDepth::Eight);

    let pixel = |x: usize, y: usize| -> &[u8] {
        let index = (y * frame.width as usize + x) * 4;
        &bytes[index..index + 4]
    };

    let corners = [
        pixel(0, 0),
        pixel(frame.width as usize - 1, 0),
        pixel(0, frame.height as usize - 1),
        pixel(frame.width as usize - 1, frame.height as usize - 1),
    ];
    assert!(
        corners.iter().all(|rgba| rgba[3] == 0),
        "tray icon corners must be transparent so macOS does not render a square template mask"
    );
    assert!(
        bytes.chunks_exact(4).any(|rgba| rgba[3] == 255),
        "tray icon must contain opaque mark pixels"
    );
}

#[test]
fn platform_icons_are_generated_from_brand_mark() {
    let repo_root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .and_then(|path| path.parent())
        .expect("repo root");
    let brand_mark =
        std::fs::read_to_string(repo_root.join("assets/brand/smspusher-logo-mark.svg"))
            .unwrap_or_default();
    let mac_icon =
        std::fs::read_to_string(repo_root.join("assets/brand/smspusher-app-icon-macos.svg"))
            .unwrap_or_default();

    assert!(brand_mark.contains("id=\"markGradient\""));
    assert!(mac_icon.contains("<rect x=\"64\" y=\"64\" width=\"896\" height=\"896\" rx=\"208\""));
    assert!(mac_icon.contains("href=\"smspusher-logo-mark.svg\""));
    assert!(repo_root
        .join("apps/tauri/resources/AppIcon.icns")
        .is_file());
    assert!(repo_root
        .join("apps/tauri/src-tauri/icons/icon.png")
        .is_file());
    assert!(repo_root
        .join("apps/tauri/src-tauri/icons/tray-icon.png")
        .is_file());
    for density in ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"] {
        assert!(repo_root
            .join(format!(
                "apps/android/src/main/res/mipmap-{density}/ic_launcher.png"
            ))
            .is_file());
        assert!(repo_root
            .join(format!(
                "apps/android/src/main/res/mipmap-{density}/ic_launcher_round.png"
            ))
            .is_file());
    }
}

#[test]
fn app_state_uses_resolved_desktop_name_for_service_name() {
    let source = include_str!("../src/app_state.rs");

    assert!(source.contains("default_desktop_service_name"));
    assert!(!source.contains("service_name: \"SmsPusher\".into()"));
}

#[test]
fn lan_server_binds_all_interfaces_for_android_pairing() {
    let source = include_str!("../src/app_state.rs");

    assert!(source.contains("host: \"0.0.0.0\".into()"));
    assert!(!source.contains("host: \"127.0.0.1\".into()"));
}
