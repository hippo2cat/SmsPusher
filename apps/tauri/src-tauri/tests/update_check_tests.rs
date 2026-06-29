use smspusher_tauri_lib::updates::{
    curl_args_for_update_request, parse_curl_progress_percentages,
    select_update_candidate_for_platform, select_update_candidate_from_manifest_json,
    update_download_path, DesktopUpdatePlatform, GitHubAsset, GitHubRelease, UpdateCheckOutcome,
    UpdateProxyConfig, UpdateProxyMode, UpdateState, UPDATE_MANIFEST_URL,
};
use std::path::{Path, PathBuf};

fn asset(name: &str) -> GitHubAsset {
    GitHubAsset {
        name: name.to_owned(),
        browser_download_url: format!("https://example.com/{name}"),
    }
}

fn release(tag_name: &str, prerelease: bool, assets: Vec<GitHubAsset>) -> GitHubRelease {
    GitHubRelease {
        tag_name: tag_name.to_owned(),
        draft: false,
        prerelease,
        assets,
    }
}

fn empty_state() -> UpdateState {
    UpdateState {
        last_opened_version: None,
    }
}

#[test]
fn update_check_selects_newer_exact_dmg_asset() {
    let release = release(
        "v1.0.1",
        false,
        vec![
            asset("SmsPusher-1.0.1.zip"),
            asset("SmsPusher-1.0.1.dmg"),
            asset("SmsPusher-1.0.1.apk"),
        ],
    );

    let candidate = select_update_candidate_for_platform(
        "1.0.0",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Macos,
    )
    .unwrap();

    assert_eq!(candidate.version, "1.0.1");
    assert_eq!(candidate.asset_name, "SmsPusher-1.0.1.dmg");
    assert_eq!(
        candidate.download_url,
        "https://example.com/SmsPusher-1.0.1.dmg"
    );
}

#[test]
fn update_check_skips_current_version() {
    let release = release("v1.0.0", false, vec![asset("SmsPusher-1.0.0.dmg")]);

    assert!(select_update_candidate_for_platform(
        "1.0.0",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Macos,
    )
    .is_none());
}

#[test]
fn update_check_skips_prerelease() {
    let release = release(
        "v1.0.1-beta.1",
        true,
        vec![asset("SmsPusher-1.0.1-beta.1.dmg")],
    );

    assert!(select_update_candidate_for_platform(
        "1.0.0",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Macos,
    )
    .is_none());
}

#[test]
fn update_check_accepts_fallback_dmg_containing_version() {
    let release = release(
        "1.0.2",
        false,
        vec![
            asset("release-notes.txt"),
            asset("SmsPusher-macos-1.0.2-universal.dmg"),
        ],
    );

    let candidate = select_update_candidate_for_platform(
        "1.0.1",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Macos,
    )
    .unwrap();

    assert_eq!(candidate.version, "1.0.2");
    assert_eq!(candidate.asset_name, "SmsPusher-macos-1.0.2-universal.dmg");
}

#[test]
fn update_check_skips_version_already_opened() {
    let release = release("v1.0.1", false, vec![asset("SmsPusher-1.0.1.dmg")]);
    let state = UpdateState {
        last_opened_version: Some("1.0.1".to_owned()),
    };

    assert!(select_update_candidate_for_platform(
        "1.0.0",
        &state,
        &release,
        DesktopUpdatePlatform::Macos,
    )
    .is_none());
}

#[test]
fn update_check_selects_newer_exact_windows_exe_asset() {
    let release = release(
        "v1.0.1",
        false,
        vec![
            asset("SmsPusher-1.0.1.dmg"),
            asset("SmsPusher-1.0.1.apk"),
            asset("SmsPusher-1.0.1-windows-x64.exe"),
        ],
    );

    let candidate = select_update_candidate_for_platform(
        "1.0.0",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Windows,
    )
    .unwrap();

    assert_eq!(candidate.version, "1.0.1");
    assert_eq!(candidate.asset_name, "SmsPusher-1.0.1-windows-x64.exe");
    assert_eq!(
        candidate.download_url,
        "https://example.com/SmsPusher-1.0.1-windows-x64.exe"
    );
}

#[test]
fn update_check_accepts_fallback_windows_exe_containing_version() {
    let release = release(
        "1.0.2",
        false,
        vec![
            asset("release-notes.txt"),
            asset("SmsPusher-1.0.2.dmg"),
            asset("SmsPusher-windows-1.0.2-setup.exe"),
        ],
    );

    let candidate = select_update_candidate_for_platform(
        "1.0.1",
        &empty_state(),
        &release,
        DesktopUpdatePlatform::Windows,
    )
    .unwrap();

    assert_eq!(candidate.version, "1.0.2");
    assert_eq!(candidate.asset_name, "SmsPusher-windows-1.0.2-setup.exe");
}

fn manifest(version: &str) -> String {
    format!(
        r#"{{
          "version": "{version}",
          "buildNumber": 12,
          "channel": "stable",
          "releaseNotesUrl": "https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/tag/v{version}",
          "platforms": {{
            "macos": {{
              "assetName": "SmsPusher-{version}.dmg",
              "downloadUrl": "https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/download/v{version}/SmsPusher-{version}.dmg"
            }},
            "windows": {{
              "assetName": "SmsPusher-{version}-windows-x64.exe",
              "downloadUrl": "https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/download/v{version}/SmsPusher-{version}-windows-x64.exe"
            }}
          }}
        }}"#
    )
}

#[test]
fn update_check_fetches_github_pages_manifest_instead_of_github_api() {
    assert_eq!(
        UPDATE_MANIFEST_URL,
        "https://hippo2cat.github.io/SmsPusher/updates/stable/latest.json"
    );
    assert!(!UPDATE_MANIFEST_URL.contains("api.github.com"));
}

#[test]
fn update_check_can_select_macos_candidate_from_pages_manifest() {
    let candidate = select_update_candidate_from_manifest_json(
        "1.0.5",
        &empty_state(),
        &manifest("1.0.6"),
        DesktopUpdatePlatform::Macos,
    )
    .unwrap()
    .unwrap();

    assert_eq!(candidate.version, "1.0.6");
    assert_eq!(candidate.asset_name, "SmsPusher-1.0.6.dmg");
    assert_eq!(
        candidate.download_url,
        "https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/download/v1.0.6/SmsPusher-1.0.6.dmg"
    );
}

#[test]
fn update_check_can_select_windows_candidate_from_pages_manifest() {
    let candidate = select_update_candidate_from_manifest_json(
        "1.0.5",
        &empty_state(),
        &manifest("1.0.6"),
        DesktopUpdatePlatform::Windows,
    )
    .unwrap()
    .unwrap();

    assert_eq!(candidate.version, "1.0.6");
    assert_eq!(candidate.asset_name, "SmsPusher-1.0.6-windows-x64.exe");
    assert_eq!(
        candidate.download_url,
        "https://github.com/hippo2cat/AndroidSmsPushToMacos/releases/download/v1.0.6/SmsPusher-1.0.6-windows-x64.exe"
    );
}

#[test]
fn update_check_manifest_candidate_skips_current_version() {
    assert!(select_update_candidate_from_manifest_json(
        "1.0.5",
        &empty_state(),
        &manifest("1.0.5"),
        DesktopUpdatePlatform::Macos,
    )
    .unwrap()
    .is_none());
}

#[test]
fn update_download_path_uses_app_managed_updates_directory() {
    let data_dir = Path::new("/tmp/smspusher-data");

    assert_eq!(
        update_download_path(data_dir, DesktopUpdatePlatform::Macos, "1.0.3"),
        PathBuf::from("/tmp/smspusher-data/updates/SmsPusher-1.0.3.dmg")
    );
    assert_eq!(
        update_download_path(data_dir, DesktopUpdatePlatform::Windows, "1.0.3"),
        PathBuf::from("/tmp/smspusher-data/updates/SmsPusher-1.0.3-windows-x64.exe")
    );
}

#[test]
fn update_proxy_none_disables_system_and_environment_proxy() {
    let proxy = UpdateProxyConfig::from_settings(UpdateProxyMode::None, "http://127.0.0.1:7890");

    assert_eq!(proxy.mode(), UpdateProxyMode::None);
    assert_eq!(
        curl_args_for_update_request(&proxy),
        vec![
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--user-agent",
            "SmsPusher/1.0.5",
            "--noproxy",
            "*",
        ]
    );
}

#[test]
fn update_proxy_system_uses_platform_default_proxy_resolution() {
    let proxy = UpdateProxyConfig::from_settings(UpdateProxyMode::System, "");

    assert_eq!(proxy.mode(), UpdateProxyMode::System);
    assert_eq!(
        curl_args_for_update_request(&proxy),
        vec![
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--user-agent",
            "SmsPusher/1.0.5",
        ]
    );
}

#[test]
fn update_proxy_manual_trims_and_feeds_curl_proxy_args() {
    let proxy =
        UpdateProxyConfig::from_settings(UpdateProxyMode::Manual, "  socks5h://127.0.0.1:7890  ");

    assert_eq!(proxy.mode(), UpdateProxyMode::Manual);
    assert_eq!(proxy.url(), Some("socks5h://127.0.0.1:7890"));
    assert_eq!(
        curl_args_for_update_request(&proxy),
        vec![
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--user-agent",
            "SmsPusher/1.0.5",
            "--proxy",
            "socks5h://127.0.0.1:7890",
        ]
    );
}

#[test]
fn manual_update_check_outcome_serializes_for_frontend_feedback() {
    assert_eq!(
        serde_json::to_string(&UpdateCheckOutcome::UpToDate).unwrap(),
        r#"{"status":"upToDate"}"#
    );
    assert_eq!(
        serde_json::to_string(&UpdateCheckOutcome::InstallerOpened {
            version: "1.0.6".to_owned(),
            asset_name: "SmsPusher-1.0.6.dmg".to_owned(),
        })
        .unwrap(),
        r#"{"status":"installerOpened","version":"1.0.6","assetName":"SmsPusher-1.0.6.dmg"}"#
    );
}

#[test]
fn curl_progress_parser_extracts_percent_updates_from_progress_bar() {
    assert_eq!(
        parse_curl_progress_percentages(
            "######################################################################## 100.0%\r\n"
        ),
        vec![100]
    );
    assert_eq!(
        parse_curl_progress_percentages("#### 5.2%\r\n#################### 42.7%\r\n"),
        vec![5, 43]
    );
}
