use chrono::{TimeZone, Utc};
use smspusher_tauri_lib::tray_presentation::{make_tray_presentation, TrayPresentationInput};

#[test]
fn formats_pairing_countdown_and_active_port() {
    let presentation = make_tray_presentation(
        TrayPresentationInput {
            pairing_code: "123456".into(),
            pairing_expires_at: Utc.timestamp_opt(700, 0).unwrap(),
            lan_port: Some(55515),
            has_attention: false,
        },
        Utc.timestamp_opt(100, 0).unwrap(),
    );

    assert_eq!(presentation.status_item_title, "SMS");
    assert_eq!(presentation.status_line, "Receiving on port 55515");
    assert_eq!(
        presentation.pairing_line,
        "Pairing Code: 123456 - expires in 10:00"
    );
}

#[test]
fn expired_pairing_countdown_is_zero_and_attention_title_is_marked() {
    let presentation = make_tray_presentation(
        TrayPresentationInput {
            pairing_code: "654321".into(),
            pairing_expires_at: Utc.timestamp_opt(100, 0).unwrap(),
            lan_port: Some(55515),
            has_attention: true,
        },
        Utc.timestamp_opt(130, 0).unwrap(),
    );

    assert_eq!(presentation.status_item_title, "SMS !");
    assert_eq!(
        presentation.pairing_line,
        "Pairing Code: 654321 - expires in 00:00"
    );
}

#[test]
fn missing_port_is_reported_as_unavailable() {
    let presentation = make_tray_presentation(
        TrayPresentationInput {
            pairing_code: "123456".into(),
            pairing_expires_at: Utc.timestamp_opt(700, 0).unwrap(),
            lan_port: None,
            has_attention: true,
        },
        Utc.timestamp_opt(100, 0).unwrap(),
    );

    assert_eq!(presentation.status_item_title, "SMS !");
    assert_eq!(presentation.status_line, "Port unavailable");
}
