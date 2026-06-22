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
fn tray_icon_uses_round_white_background_asset() {
    let source = include_str!("../src/lib.rs");
    let icon_path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("icons")
        .join("tray-icon-white-bg-circle.png");

    assert!(icon_path.exists());
    assert!(source.contains("include_image!(\"./icons/tray-icon-white-bg-circle.png\")"));
    assert!(source.contains(".icon_as_template(false)"));
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
fn tray_popover_exposes_autostart_toggle() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let cargo = std::fs::read_to_string(tauri_dir.join("src-tauri/Cargo.toml")).unwrap_or_default();
    let lib = std::fs::read_to_string(tauri_dir.join("src-tauri/src/lib.rs")).unwrap_or_default();
    let capabilities =
        std::fs::read_to_string(tauri_dir.join("src-tauri/capabilities/default.json"))
            .unwrap_or_default();
    let package = std::fs::read_to_string(tauri_dir.join("package.json")).unwrap_or_default();
    let tauri_api = std::fs::read_to_string(tauri_dir.join("src/tauri.ts")).unwrap_or_default();
    let tray = std::fs::read_to_string(tauri_dir.join("src/TrayPopover.tsx")).unwrap_or_default();
    let styles = std::fs::read_to_string(tauri_dir.join("src/styles.css")).unwrap_or_default();
    let en = std::fs::read_to_string(tauri_dir.join("src/i18n/generated/en-US.json"))
        .unwrap_or_default();
    let zh = std::fs::read_to_string(tauri_dir.join("src/i18n/generated/zh-CN.json"))
        .unwrap_or_default();

    assert!(cargo.contains("tauri-plugin-autostart"));
    assert!(lib.contains("tauri_plugin_autostart::MacosLauncher::LaunchAgent"));
    assert!(lib.contains("tauri_plugin_autostart::init"));
    assert!(capabilities.contains("\"autostart:allow-enable\""));
    assert!(capabilities.contains("\"autostart:allow-disable\""));
    assert!(capabilities.contains("\"autostart:allow-is-enabled\""));
    assert!(package.contains("\"@tauri-apps/plugin-autostart\""));
    assert!(tauri_api.contains("@tauri-apps/plugin-autostart"));
    assert!(tauri_api.contains("getAutostartEnabled"));
    assert!(tauri_api.contains("setAutostartEnabled"));
    assert!(tray.contains("autostartEnabled"));
    assert!(tray.contains("toggleAutostart"));
    assert!(tray.contains("t(\"tray.autostart\")"));
    assert!(styles.contains(".settings-toggle-row"));
    assert!(en.contains("\"tray.autostart\": \"Open at login\""));
    assert!(zh.contains("\"tray.autostart\": \"开机自启动\""));
}

#[test]
fn macos_update_check_downloads_github_release_dmg_without_tauri_updater() {
    let tauri_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    let cargo = std::fs::read_to_string(tauri_dir.join("src-tauri/Cargo.toml")).unwrap_or_default();
    let package = std::fs::read_to_string(tauri_dir.join("package.json")).unwrap_or_default();
    let lib = std::fs::read_to_string(tauri_dir.join("src-tauri/src/lib.rs")).unwrap_or_default();
    let updates =
        std::fs::read_to_string(tauri_dir.join("src-tauri/src/updates.rs")).unwrap_or_default();

    assert!(lib.contains("pub mod updates;"));
    assert!(lib.contains("updates::start_macos_update_check(data_dir.clone())"));
    assert!(updates
        .contains("https://api.github.com/repos/hippo2cat/AndroidSmsPushToMacos/releases/latest"));
    assert!(updates.contains("SmsPusher-{version}.dmg"));
    assert!(updates.contains("update_state.json"));
    assert!(updates.contains("Command::new(CURL_PATH)"));
    assert!(updates.contains("Command::new(OPEN_PATH)"));
    assert!(!cargo.contains("tauri-plugin-updater"));
    assert!(!package.contains("@tauri-apps/plugin-updater"));
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
    assert!(source.contains("tauri::include_image!(\"./icons/tray-icon-white-bg-circle.png\")"));
    assert!(source.contains(".icon(TRAY_ICON.clone())"));
    assert!(source.contains(".icon_as_template(false)"));
    assert!(!source.contains(".title(&title)"));
    assert!(!source.contains("current_tray_title"));
}

#[test]
fn tray_icon_png_has_round_white_background_for_non_template_rendering() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let tray_icon = manifest_dir.join("icons/tray-icon-white-bg-circle.png");
    let source_icon = manifest_dir.join("icons/tray-icon.png");
    let file = File::open(&tray_icon).expect("tray icon png");
    let decoder = png::Decoder::new(BufReader::new(file));
    let mut reader = decoder.read_info().expect("png metadata");
    let mut buffer = vec![0; reader.output_buffer_size().expect("png output buffer size")];
    let frame = reader.next_frame(&mut buffer).expect("png frame");
    let bytes = &buffer[..frame.buffer_size()];

    let source_file = File::open(&source_icon).expect("source tray icon png");
    let source_decoder = png::Decoder::new(BufReader::new(source_file));
    let mut source_reader = source_decoder.read_info().expect("source png metadata");
    let mut source_buffer = vec![
        0;
        source_reader
            .output_buffer_size()
            .expect("source png output buffer size")
    ];
    let source_frame = source_reader
        .next_frame(&mut source_buffer)
        .expect("source png frame");
    let source_bytes = &source_buffer[..source_frame.buffer_size()];

    assert_eq!((frame.width, frame.height), (64, 64));
    assert_eq!(frame.color_type, png::ColorType::Rgba);
    assert_eq!(frame.bit_depth, png::BitDepth::Eight);
    assert_eq!((source_frame.width, source_frame.height), (64, 64));
    assert_eq!(source_frame.color_type, png::ColorType::Rgba);

    let pixel = |x: usize, y: usize| -> &[u8] {
        let index = (y * frame.width as usize + x) * 4;
        &bytes[index..index + 4]
    };
    let is_white_background =
        |rgba: &[u8]| rgba[0] >= 245 && rgba[1] >= 245 && rgba[2] >= 245 && rgba[3] == 255;
    let is_inside_round_background = |x: usize, y: usize| {
        let dx = x as f64 + 0.5 - 32.0;
        let dy = y as f64 + 0.5 - 32.0;
        (dx * dx + dy * dy) <= 28.0 * 28.0
    };
    let bbox = |points: &[(usize, usize)]| {
        let min_x = points.iter().map(|(x, _)| *x).min().unwrap();
        let max_x = points.iter().map(|(x, _)| *x).max().unwrap();
        let min_y = points.iter().map(|(_, y)| *y).min().unwrap();
        let max_y = points.iter().map(|(_, y)| *y).max().unwrap();
        (min_x, max_x, min_y, max_y)
    };

    let corners = [
        pixel(0, 0),
        pixel(frame.width as usize - 1, 0),
        pixel(0, frame.height as usize - 1),
        pixel(frame.width as usize - 1, frame.height as usize - 1),
    ];
    assert!(
        corners.iter().all(|rgba| rgba[3] == 0),
        "tray icon corners must stay transparent so the round white background is visible"
    );
    assert!(
        bytes.chunks_exact(4).any(is_white_background),
        "tray icon must contain opaque white background pixels"
    );
    assert!(
        bytes.chunks_exact(4).all(|rgba| {
            rgba[3] < 180
                || is_white_background(rgba)
                || rgba[0].min(rgba[1]).min(rgba[2]) >= 180
        }),
        "tray icon must not draw a dark/colored mark; the mark should be transparent cutout"
    );

    let cutout_points: Vec<(usize, usize)> = bytes
        .chunks_exact(4)
        .enumerate()
        .filter_map(|(index, rgba)| {
            let x = index % frame.width as usize;
            let y = index / frame.width as usize;
            if is_inside_round_background(x, y) && rgba[3] <= 24 {
                return Some((x, y));
            }
            None
        })
        .collect();
    assert!(
        cutout_points.len() > 220,
        "tray icon must punch the original mark through the white circle"
    );

    let source_alpha_points: Vec<(usize, usize)> = source_bytes
        .chunks_exact(4)
        .enumerate()
        .filter_map(|(index, rgba)| {
            if rgba[3] > 24 {
                Some((
                    index % source_frame.width as usize,
                    index / source_frame.width as usize,
                ))
            } else {
                None
            }
        })
        .collect();
    let (source_min_x, source_max_x, source_min_y, source_max_y) = bbox(&source_alpha_points);
    let (cutout_min_x, cutout_max_x, cutout_min_y, cutout_max_y) = bbox(&cutout_points);
    let source_ratio =
        (source_max_x - source_min_x) as f64 / (source_max_y - source_min_y) as f64;
    let cutout_width = cutout_max_x - cutout_min_x;
    let cutout_height = cutout_max_y - cutout_min_y;
    let cutout_ratio = cutout_width as f64 / cutout_height as f64;

    assert!(
        (30..=38).contains(&cutout_width) && (32..=42).contains(&cutout_height),
        "tray icon cutout should be medium-sized, not tiny or oversized"
    );
    assert!(
        (cutout_ratio - source_ratio).abs() <= 0.2,
        "tray icon cutout should keep the original tray-icon.png shape proportions"
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
    assert!(repo_root
        .join("apps/tauri/src-tauri/icons/tray-icon-white-bg-circle.png")
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
