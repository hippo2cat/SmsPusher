use smspusher_tauri_lib::lan_diagnostics::{
    lan_diagnostics_for_platform, DesktopPlatform, LanDiagnosticWarning, LanDiagnosticWarningKind,
};

#[test]
fn windows_lan_server_reports_firewall_hint_with_active_port() {
    let diagnostics =
        lan_diagnostics_for_platform(DesktopPlatform::Windows, true, Some(55515), false);

    assert_eq!(
        diagnostics.warnings,
        vec![LanDiagnosticWarning {
            kind: LanDiagnosticWarningKind::WindowsFirewall,
            port: Some(55515),
        }]
    );
}

#[test]
fn windows_firewall_hint_is_hidden_when_lan_is_disabled_or_missing_port() {
    assert!(
        lan_diagnostics_for_platform(DesktopPlatform::Windows, false, Some(55515), false)
            .warnings
            .is_empty()
    );
    assert!(
        lan_diagnostics_for_platform(DesktopPlatform::Windows, true, None, false)
            .warnings
            .is_empty()
    );
}

#[test]
fn non_windows_platforms_do_not_show_windows_firewall_hint() {
    for platform in [
        DesktopPlatform::Macos,
        DesktopPlatform::Linux,
        DesktopPlatform::Other,
    ] {
        assert!(
            lan_diagnostics_for_platform(platform, true, Some(55515), false)
                .warnings
                .is_empty()
        );
    }
}

#[test]
fn stale_network_interface_reports_warning_on_all_platforms() {
    let diagnostics = lan_diagnostics_for_platform(DesktopPlatform::Macos, true, Some(55515), true);

    assert_eq!(
        diagnostics.warnings,
        vec![LanDiagnosticWarning {
            kind: LanDiagnosticWarningKind::StaleNetworkInterface,
            port: None,
        }]
    );
}
