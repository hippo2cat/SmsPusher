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
