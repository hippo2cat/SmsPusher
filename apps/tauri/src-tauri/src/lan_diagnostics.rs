use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopPlatform {
    Macos,
    Windows,
    Linux,
    Other,
}

impl DesktopPlatform {
    pub fn current() -> Self {
        if cfg!(target_os = "macos") {
            Self::Macos
        } else if cfg!(windows) {
            Self::Windows
        } else if cfg!(target_os = "linux") {
            Self::Linux
        } else {
            Self::Other
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanDiagnosticsSnapshot {
    pub warnings: Vec<LanDiagnosticWarning>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LanDiagnosticWarning {
    pub kind: LanDiagnosticWarningKind,
    pub port: Option<u16>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum LanDiagnosticWarningKind {
    WindowsFirewall,
}

pub fn lan_diagnostics_for_platform(
    platform: DesktopPlatform,
    lan_enabled: bool,
    lan_port: Option<u16>,
) -> LanDiagnosticsSnapshot {
    let mut warnings = Vec::new();
    if platform == DesktopPlatform::Windows && lan_enabled && lan_port.is_some() {
        warnings.push(LanDiagnosticWarning {
            kind: LanDiagnosticWarningKind::WindowsFirewall,
            port: lan_port,
        });
    }

    LanDiagnosticsSnapshot { warnings }
}
