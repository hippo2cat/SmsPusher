use smspusher_tauri_lib::updates::{
    select_update_candidate_for_platform, update_download_path, DesktopUpdatePlatform, GitHubAsset,
    GitHubRelease, UpdateState,
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

    assert!(
        select_update_candidate_for_platform(
            "1.0.0",
            &empty_state(),
            &release,
            DesktopUpdatePlatform::Macos,
        )
        .is_none()
    );
}

#[test]
fn update_check_skips_prerelease() {
    let release = release(
        "v1.0.1-beta.1",
        true,
        vec![asset("SmsPusher-1.0.1-beta.1.dmg")],
    );

    assert!(
        select_update_candidate_for_platform(
            "1.0.0",
            &empty_state(),
            &release,
            DesktopUpdatePlatform::Macos,
        )
        .is_none()
    );
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

    assert!(
        select_update_candidate_for_platform(
            "1.0.0",
            &state,
            &release,
            DesktopUpdatePlatform::Macos,
        )
        .is_none()
    );
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
