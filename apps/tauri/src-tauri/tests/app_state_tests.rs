use smspusher_tauri_lib::app_state::{
    desktop_service_name_from_candidates, AppSettingsUpdate, SmsPusherAppState,
};
use smspusher_tauri_lib::i18n::{resolve_locale, LanguagePreference, SUPPORTED_LOCALES};

#[test]
fn desktop_service_name_prefers_system_computer_name_candidate() {
    let service_name = desktop_service_name_from_candidates([" Test Desktop ", "SmsPusher"]);

    assert_eq!(service_name, "Test Desktop");
}

#[test]
fn desktop_service_name_falls_back_to_product_name_when_no_system_name_exists() {
    let service_name = desktop_service_name_from_candidates(Vec::<&str>::new());

    assert_eq!(service_name, "SmsPusher");
}

#[test]
fn app_state_exposes_status_and_refreshes_pairing_code() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();
    let first = state.get_status().unwrap();

    let refreshed = state.refresh_pairing_code().unwrap();
    let second = state.get_status().unwrap();

    assert_eq!(refreshed.len(), 6);
    assert_ne!(first.pairing_code.value, second.pairing_code.value);
}

#[test]
fn update_settings_changes_preferred_port_and_history_limit() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();

    let settings = state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(55444),
            history_limit: Some(250),
            lan_enabled: Some(false),
            notifications_enabled: Some(false),
            network_interface_id: Some(Some("en0@192.0.2.10".into())),
            language_preference: None,
        })
        .unwrap();

    assert_eq!(settings.preferred_port, 55444);
    assert_eq!(settings.history_limit, 250);
    assert!(!settings.lan_enabled);
    assert!(!settings.notifications_enabled);
    assert_eq!(
        settings.network_interface_id.as_deref(),
        Some("en0@192.0.2.10")
    );
    assert_eq!(state.get_status().unwrap().preferred_port, 55444);
    assert_eq!(
        state.revoke_device("missing").unwrap().preferred_port,
        55444
    );
}

#[test]
fn update_settings_persists_across_app_state_recreation() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();

    state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(55444),
            history_limit: Some(250),
            lan_enabled: Some(false),
            notifications_enabled: Some(false),
            network_interface_id: Some(Some("en0@192.0.2.10".into())),
            language_preference: None,
        })
        .unwrap();

    let recreated = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();
    let settings = recreated.settings();

    assert_eq!(settings.preferred_port, 55444);
    assert_eq!(settings.history_limit, 250);
    assert!(!settings.lan_enabled);
    assert!(!settings.notifications_enabled);
    assert_eq!(
        settings.network_interface_id.as_deref(),
        Some("en0@192.0.2.10")
    );
}

#[test]
fn update_settings_can_clear_selected_network_interface() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();

    state
        .update_settings(AppSettingsUpdate {
            preferred_port: None,
            history_limit: None,
            lan_enabled: None,
            notifications_enabled: None,
            network_interface_id: Some(Some("en0@192.0.2.10".into())),
            language_preference: None,
        })
        .unwrap();
    let settings = state
        .update_settings(AppSettingsUpdate {
            preferred_port: None,
            history_limit: None,
            lan_enabled: None,
            notifications_enabled: None,
            network_interface_id: Some(None),
            language_preference: None,
        })
        .unwrap();

    assert_eq!(settings.network_interface_id, None);
}

#[test]
fn app_state_uses_swift_compatible_message_database_filename() {
    let temp = tempfile::tempdir().unwrap();

    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();

    assert_eq!(state.data_dir(), temp.path());
    assert!(temp.path().join("messages.sqlite").exists());
    assert!(!temp.path().join("messages.sqlite3").exists());
}

#[test]
fn language_preference_defaults_to_auto_and_persists() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();

    assert_eq!(
        state.settings().language_preference,
        LanguagePreference::Auto
    );

    state
        .update_settings(AppSettingsUpdate {
            preferred_port: None,
            history_limit: None,
            lan_enabled: None,
            notifications_enabled: None,
            network_interface_id: None,
            language_preference: Some(LanguagePreference::ZhCn),
        })
        .unwrap();

    let recreated = SmsPusherAppState::new_for_data_dir(temp.path()).unwrap();
    assert_eq!(
        recreated.settings().language_preference,
        LanguagePreference::ZhCn
    );
}

#[test]
fn desktop_locale_resolution_follows_preference_then_system_then_english() {
    assert_eq!(SUPPORTED_LOCALES, ["en-US", "zh-CN"]);
    assert_eq!(resolve_locale(LanguagePreference::ZhCn, ["en-US"]), "zh-CN");
    assert_eq!(resolve_locale(LanguagePreference::EnUs, ["zh-CN"]), "en-US");
    assert_eq!(resolve_locale(LanguagePreference::Auto, ["zh-CN"]), "zh-CN");
    assert_eq!(
        resolve_locale(LanguagePreference::Auto, ["zh-Hans-CN"]),
        "zh-CN"
    );
    assert_eq!(resolve_locale(LanguagePreference::Auto, ["zh"]), "zh-CN");
    assert_eq!(resolve_locale(LanguagePreference::Auto, ["fr-FR"]), "en-US");
}

#[tokio::test]
async fn start_lan_server_updates_transport_status_and_serves_health() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir_with_bonjour(temp.path(), false).unwrap();
    state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(0),
            history_limit: None,
            lan_enabled: Some(true),
            notifications_enabled: None,
            network_interface_id: None,
            language_preference: None,
        })
        .unwrap();

    let transport = state.start_lan_server().await.unwrap();

    let port = transport.lan_port.unwrap();
    assert_eq!(transport.status, "running");
    assert_ne!(port, 0);
    let response = reqwest::get(format!("http://127.0.0.1:{port}/health"))
        .await
        .unwrap();
    assert_eq!(response.status(), reqwest::StatusCode::OK);
    assert_eq!(response.text().await.unwrap(), r#"{"status":"ok"}"#);

    state.stop_lan_server().await.unwrap();
}

#[tokio::test]
async fn stop_lan_server_marks_transport_stopped() {
    let temp = tempfile::tempdir().unwrap();
    let state = SmsPusherAppState::new_for_data_dir_with_bonjour(temp.path(), false).unwrap();
    state
        .update_settings(AppSettingsUpdate {
            preferred_port: Some(0),
            history_limit: None,
            lan_enabled: Some(true),
            notifications_enabled: None,
            network_interface_id: None,
            language_preference: None,
        })
        .unwrap();

    state.start_lan_server().await.unwrap();
    let stopped = state.stop_lan_server().await.unwrap();

    assert_eq!(stopped.status, "stopped");
    assert_eq!(stopped.lan_port, None);
}
