use chrono::{TimeZone, Utc};
use smspusher_tauri_lib::tray_presentation::{
    make_tray_presentation, popover_position, screen_for_anchor, PopoverAnchorRect,
    PopoverGeometry, ScreenFrame, TrayPresentationInput,
};

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

#[test]
fn popover_position_opens_below_top_menu_bar_anchor() {
    let position = popover_position(
        PopoverAnchorRect {
            x: 900,
            y: 0,
            width: 32,
            height: 24,
        },
        Some(ScreenFrame {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        }),
        PopoverGeometry {
            width: 376,
            height: 530,
            margin: 10,
        },
    );

    assert_eq!(position.x, 728);
    assert_eq!(position.y, 24);
}

#[test]
fn popover_position_opens_above_windows_bottom_taskbar_anchor() {
    let position = popover_position(
        PopoverAnchorRect {
            x: 1840,
            y: 1032,
            width: 40,
            height: 40,
        },
        Some(ScreenFrame {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        }),
        PopoverGeometry {
            width: 376,
            height: 530,
            margin: 10,
        },
    );

    assert_eq!(position.x, 1534);
    assert_eq!(position.y, 492);
}

#[test]
fn popover_position_clamps_to_screen_margins() {
    let left = popover_position(
        PopoverAnchorRect {
            x: 4,
            y: 0,
            width: 24,
            height: 24,
        },
        Some(ScreenFrame {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        }),
        PopoverGeometry {
            width: 376,
            height: 530,
            margin: 10,
        },
    );
    let right = popover_position(
        PopoverAnchorRect {
            x: 1910,
            y: 0,
            width: 24,
            height: 24,
        },
        Some(ScreenFrame {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        }),
        PopoverGeometry {
            width: 376,
            height: 530,
            margin: 10,
        },
    );

    assert_eq!(left.x, 10);
    assert_eq!(right.x, 1534);
}

#[test]
fn screen_for_anchor_prefers_secondary_monitor_containing_tray_anchor() {
    let screen = screen_for_anchor(
        PopoverAnchorRect {
            x: 3160,
            y: 1390,
            width: 40,
            height: 40,
        },
        &[
            ScreenFrame {
                x: 0,
                y: 0,
                width: 2560,
                height: 1440,
            },
            ScreenFrame {
                x: 2560,
                y: 0,
                width: 1920,
                height: 1440,
            },
        ],
    );

    assert_eq!(
        screen,
        Some(ScreenFrame {
            x: 2560,
            y: 0,
            width: 1920,
            height: 1440,
        })
    );
}

#[test]
fn screen_for_anchor_uses_nearest_monitor_when_anchor_is_outside_all_frames() {
    let screen = screen_for_anchor(
        PopoverAnchorRect {
            x: -56,
            y: 980,
            width: 40,
            height: 40,
        },
        &[
            ScreenFrame {
                x: 0,
                y: 0,
                width: 1920,
                height: 1080,
            },
            ScreenFrame {
                x: 1920,
                y: 0,
                width: 1920,
                height: 1080,
            },
        ],
    );

    assert_eq!(
        screen,
        Some(ScreenFrame {
            x: 0,
            y: 0,
            width: 1920,
            height: 1080,
        })
    );
}
