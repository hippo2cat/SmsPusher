use smspusher_tauri_lib::updates::{
    select_update_candidate, GitHubAsset, GitHubRelease, UpdateState,
};

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

    let candidate = select_update_candidate("1.0.0", &empty_state(), &release).unwrap();

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

    assert!(select_update_candidate("1.0.0", &empty_state(), &release).is_none());
}

#[test]
fn update_check_skips_prerelease() {
    let release = release(
        "v1.0.1-beta.1",
        true,
        vec![asset("SmsPusher-1.0.1-beta.1.dmg")],
    );

    assert!(select_update_candidate("1.0.0", &empty_state(), &release).is_none());
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

    let candidate = select_update_candidate("1.0.1", &empty_state(), &release).unwrap();

    assert_eq!(candidate.version, "1.0.2");
    assert_eq!(candidate.asset_name, "SmsPusher-macos-1.0.2-universal.dmg");
}

#[test]
fn update_check_skips_version_already_opened() {
    let release = release("v1.0.1", false, vec![asset("SmsPusher-1.0.1.dmg")]);
    let state = UpdateState {
        last_opened_version: Some("1.0.1".to_owned()),
    };

    assert!(select_update_candidate("1.0.0", &state, &release).is_none());
}
