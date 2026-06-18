use smspusher_tauri_lib::storage::{
    prepare_sms_pusher_data_dir, resolve_sms_pusher_data_dir, LEGACY_SUPPORT_DIRECTORY_NAME,
    SUPPORT_DIRECTORY_NAME,
};
use std::fs;

#[test]
fn app_state_reports_secure_transport_protocol() {
    let temp = tempfile::tempdir().unwrap();
    let state = smspusher_tauri_lib::app_state::SmsPusherAppState::new_for_data_dir_with_bonjour(
        temp.path(),
        false,
    )
    .unwrap();

    let status = state.get_status().unwrap();

    assert_eq!(
        status.transport.secure_protocol.as_deref(),
        Some("lan-secure-v2")
    );
}

#[test]
fn resolves_tauri_identifier_directory_to_sms_pusher_sibling() {
    let path = resolve_sms_pusher_data_dir(
        "/Users/example/Library/Application Support/com.jbz.smspusher".into(),
    );

    assert_eq!(
        path.to_string_lossy(),
        "/Users/example/Library/Application Support/SmsPusher"
    );
}

#[test]
fn keeps_existing_sms_pusher_directory_name() {
    let path =
        resolve_sms_pusher_data_dir("/Users/example/Library/Application Support/SmsPusher".into());

    assert_eq!(
        path.to_string_lossy(),
        "/Users/example/Library/Application Support/SmsPusher"
    );
}

#[test]
fn prepare_data_dir_copies_legacy_files_without_overwriting_existing_files() {
    let temp = tempfile::tempdir().unwrap();
    let support = temp.path().join(SUPPORT_DIRECTORY_NAME);
    let legacy = temp.path().join(LEGACY_SUPPORT_DIRECTORY_NAME);
    fs::create_dir_all(&support).unwrap();
    fs::create_dir_all(&legacy).unwrap();
    fs::write(legacy.join("devices.json"), "legacy-devices").unwrap();
    fs::write(legacy.join("messages.sqlite"), "legacy-messages").unwrap();
    fs::write(support.join("devices.json"), "current-devices").unwrap();

    prepare_sms_pusher_data_dir(&support).unwrap();

    assert_eq!(
        fs::read_to_string(support.join("devices.json")).unwrap(),
        "current-devices"
    );
    assert_eq!(
        fs::read_to_string(support.join("messages.sqlite")).unwrap(),
        "legacy-messages"
    );
}
