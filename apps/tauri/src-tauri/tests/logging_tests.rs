use smspusher_tauri_lib::logging::recent_log_text;
use std::{fs, time::Duration};

#[test]
fn recent_log_text_returns_empty_for_missing_log_directory() {
    let dir = tempfile::tempdir().unwrap();

    let text = recent_log_text(dir.path(), 1024);

    assert!(text.is_empty());
}

#[test]
fn recent_log_text_returns_bounded_current_and_rolled_logs() {
    let dir = tempfile::tempdir().unwrap();
    let logs = dir.path().join("logs");
    fs::create_dir_all(&logs).unwrap();
    fs::write(logs.join("smspusher.2026-06-18.log"), "old-line\n").unwrap();
    std::thread::sleep(Duration::from_millis(2));
    fs::write(logs.join("smspusher.log"), "new-line\n").unwrap();

    let text = recent_log_text(dir.path(), 128);

    assert!(text.contains("old-line"));
    assert!(text.contains("new-line"));
    assert!(text.len() <= 128);
}
