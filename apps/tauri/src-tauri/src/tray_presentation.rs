use chrono::{DateTime, Utc};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrayPresentationInput {
    pub pairing_code: String,
    pub pairing_expires_at: DateTime<Utc>,
    pub lan_port: Option<u16>,
    pub has_attention: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrayPresentation {
    pub status_item_title: String,
    pub status_line: String,
    pub pairing_line: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PopoverAnchorRect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScreenFrame {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PopoverGeometry {
    pub width: i32,
    pub height: i32,
    pub margin: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PopoverPoint {
    pub x: i32,
    pub y: i32,
}

pub fn make_tray_presentation(
    input: TrayPresentationInput,
    now: DateTime<Utc>,
) -> TrayPresentation {
    let remaining = input
        .pairing_expires_at
        .signed_duration_since(now)
        .num_seconds()
        .max(0);
    let minutes = remaining / 60;
    let seconds = remaining % 60;

    TrayPresentation {
        status_item_title: if input.has_attention {
            "SMS !".into()
        } else {
            "SMS".into()
        },
        status_line: input
            .lan_port
            .map(|port| format!("Receiving on port {port}"))
            .unwrap_or_else(|| "Port unavailable".into()),
        pairing_line: format!(
            "Pairing Code: {} - expires in {minutes:02}:{seconds:02}",
            input.pairing_code
        ),
    }
}

pub fn popover_position(
    anchor: PopoverAnchorRect,
    screen: Option<ScreenFrame>,
    geometry: PopoverGeometry,
) -> PopoverPoint {
    let fallback_screen = ScreenFrame {
        x: 0,
        y: 0,
        width: i32::MAX / 4,
        height: i32::MAX / 4,
    };
    let screen = screen.unwrap_or(fallback_screen);
    let min_x = screen.x + geometry.margin;
    let max_x = screen.x + screen.width - geometry.width - geometry.margin;
    let centered_x = anchor.x + (anchor.width - geometry.width) / 2;
    let x = centered_x.clamp(min_x, max_x.max(min_x));

    let anchor_center_y = anchor.y + anchor.height / 2;
    let screen_center_y = screen.y + screen.height / 2;
    let opens_above = anchor_center_y > screen_center_y;
    let preferred_y = if opens_above {
        anchor.y - geometry.height - geometry.margin
    } else {
        anchor.y + anchor.height
    };
    let min_y = screen.y + geometry.margin;
    let max_y = screen.y + screen.height - geometry.height - geometry.margin;
    let y = preferred_y.clamp(min_y, max_y.max(min_y));

    PopoverPoint { x, y }
}

pub fn screen_for_anchor(
    anchor: PopoverAnchorRect,
    screens: &[ScreenFrame],
) -> Option<ScreenFrame> {
    let anchor_center_x = anchor.x + anchor.width / 2;
    let anchor_center_y = anchor.y + anchor.height / 2;

    screens
        .iter()
        .copied()
        .find(|screen| {
            anchor_center_x >= screen.x
                && anchor_center_x < screen.x + screen.width
                && anchor_center_y >= screen.y
                && anchor_center_y < screen.y + screen.height
        })
        .or_else(|| {
            screens.iter().copied().min_by_key(|screen| {
                let nearest_x = anchor_center_x.clamp(screen.x, screen.x + screen.width);
                let nearest_y = anchor_center_y.clamp(screen.y, screen.y + screen.height);
                let dx = i64::from(anchor_center_x - nearest_x);
                let dy = i64::from(anchor_center_y - nearest_y);
                dx * dx + dy * dy
            })
        })
}
