use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .expect("repo root")
}

fn relative(path: &Path) -> String {
    path.strip_prefix(repo_root())
        .expect("repo-relative path")
        .to_string_lossy()
        .replace('\\', "/")
}

fn assert_file(path: &str) {
    assert!(repo_root().join(path).is_file(), "missing file: {path}");
}

fn assert_dir(path: &str) {
    assert!(repo_root().join(path).is_dir(), "missing directory: {path}");
}

fn assert_absent(path: &str) {
    assert!(
        !repo_root().join(path).exists(),
        "unexpected root-level path still exists: {path}"
    );
}

fn assert_contains(path: &str, pattern: &str) {
    let content = std::fs::read_to_string(repo_root().join(path)).unwrap_or_default();
    assert!(
        content.contains(pattern),
        "missing expected text in {path}: {pattern}"
    );
}

fn collect_shell_scripts(dir: &Path, scripts: &mut BTreeSet<String>) {
    if !dir.exists() {
        return;
    }
    for entry in std::fs::read_dir(dir).expect("read script directory") {
        let entry = entry.expect("script directory entry");
        let path = entry.path();
        if path.is_dir() {
            collect_shell_scripts(&path, scripts);
        } else if path.extension().and_then(|ext| ext.to_str()) == Some("sh") {
            scripts.insert(relative(&path));
        }
    }
}

#[test]
fn first_party_shell_scripts_are_only_operational_tools() {
    let root = repo_root();
    let mut scripts = BTreeSet::new();
    collect_shell_scripts(&root.join("scripts"), &mut scripts);
    collect_shell_scripts(&root.join("apps/android/scripts"), &mut scripts);
    collect_shell_scripts(&root.join("apps/tauri/scripts"), &mut scripts);

    let allowed = BTreeSet::from([
        "apps/android/scripts/build-android-release.sh".to_owned(),
        "apps/android/scripts/generate-android-release-keystore.sh".to_owned(),
        "apps/tauri/scripts/package-tauri-macos-app.sh".to_owned(),
        "scripts/bump-version.sh".to_owned(),
        "scripts/dev-env.sh".to_owned(),
    ]);

    assert_eq!(scripts, allowed);
}

#[test]
fn repository_layout_matches_current_app_boundaries() {
    assert_dir("apps/android");
    assert_dir("apps/android/scripts");
    assert_dir("apps/tauri");
    assert_dir("apps/tauri/resources");
    assert_dir("apps/tauri/scripts");
    assert_dir("services/desktop-rust");
    assert_dir("services/desktop-rust/crates/smspusher-core");
    assert_dir("services/desktop-rust/crates/smspusher-service");
    assert_dir("services/desktop-rust/crates/transport-lan");

    assert_absent("android-app");
    assert_absent("apps/macos");
    assert_absent("macos");
    assert_absent("gradlew");
    assert_absent("gradlew.bat");
    assert_absent("gradle");
    assert_absent("settings.gradle.kts");
    assert_absent("build.gradle.kts");
    assert_absent("local.properties");
    assert_absent(".gradle");
    assert_absent("build");
    assert_absent("Cargo.toml");
    assert_absent("Cargo.lock");
    assert_absent("crates");
    assert_absent(".DS_Store");

    assert_file("apps/android/gradlew");
    assert_file("apps/android/gradlew.bat");
    assert_dir("apps/android/gradle");
    assert_file("apps/android/settings.gradle.kts");
    assert_file("apps/android/build.gradle.kts");
    assert_file("version.properties");
    assert_file("scripts/bump-version.sh");
    assert_file("apps/android/scripts/build-android-release.sh");
    assert_file("apps/android/scripts/generate-android-release-keystore.sh");
    assert_file("apps/tauri/resources/AppIcon.icns");
    assert_file("apps/tauri/dist/index.html");
    assert_file("apps/tauri/dist/main.js");
    assert_file("apps/tauri/dist/styles.css");
    assert_file("apps/tauri/src-tauri/Cargo.toml");
    assert_file("apps/tauri/src-tauri/tauri.conf.json");
    assert_file("apps/tauri/src-tauri/src/event_pump.rs");
    assert_file("apps/tauri/src-tauri/src/lib.rs");
    assert_file("apps/tauri/src-tauri/src/main.rs");
    assert_file("apps/tauri/src-tauri/src/notifications.rs");
    assert_file("apps/tauri/src-tauri/tests/lan_parity_tests.rs");
    assert_file("apps/tauri/scripts/package-tauri-macos-app.sh");
    assert_file("services/desktop-rust/Cargo.toml");
    assert_file("services/desktop-rust/Cargo.lock");
    assert_file("services/desktop-rust/crates/smspusher-core/Cargo.toml");
    assert_file("services/desktop-rust/crates/smspusher-service/Cargo.toml");
    assert_file("services/desktop-rust/crates/transport-lan/Cargo.toml");

    assert_contains(
        "apps/android/settings.gradle.kts",
        "rootProject.name = \"SmsPusher\"",
    );
    assert_contains(
        "scripts/dev-env.sh",
        "GRADLE_USER_HOME=\"$ROOT_DIR/apps/android/.gradle\"",
    );
    assert_contains(
        "apps/tauri/src-tauri/tauri.conf.json",
        "\"productName\": \"SmsPusher\"",
    );
    assert_contains(
        "apps/tauri/src-tauri/tauri.conf.json",
        "\"withGlobalTauri\": true",
    );
    assert_contains(
        "apps/tauri/src-tauri/Cargo.toml",
        "../../../services/desktop-rust/crates/smspusher-service",
    );
    assert_contains(
        "apps/tauri/src-tauri/Cargo.toml",
        "../../../services/desktop-rust/crates/transport-lan",
    );
    assert_contains(
        "apps/tauri/src-tauri/Cargo.toml",
        "tauri-plugin-notification",
    );
    assert_contains(
        "apps/tauri/src-tauri/tests/lan_parity_tests.rs",
        "secure_lan_pairing_and_message_flow_accepts_encrypted_sms",
    );
    assert_contains(
        "apps/tauri/src-tauri/tests/lan_parity_tests.rs",
        "v1_routes_require_secure_pairing_in_production",
    );
    assert_contains(
        "apps/tauri/src-tauri/tests/lan_parity_tests.rs",
        "secure_pairing_required",
    );
    assert_contains(
        "apps/tauri/src-tauri/tests/lan_parity_tests.rs",
        "verification_code.as_deref()",
    );
    assert_contains(
        "apps/tauri/src-tauri/capabilities/default.json",
        "notification:default",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "BUNDLE_IDENTIFIER=\"${BUNDLE_IDENTIFIER:-com.jbz.smspusher}\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "APP_ICON=\"$TAURI_DIR/resources/AppIcon.icns\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "NSBonjourServices",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "NSLocalNetworkUsageDescription",
    );
    assert_contains(
        "services/desktop-rust/Cargo.toml",
        "\"crates/smspusher-core\"",
    );
    assert_contains(
        "services/desktop-rust/Cargo.toml",
        "\"crates/smspusher-service\"",
    );
    assert_contains(
        "services/desktop-rust/Cargo.toml",
        "\"crates/transport-lan\"",
    );
}

#[test]
fn version_management_uses_shared_version_file() {
    assert_file("version.properties");
    assert_file("scripts/bump-version.sh");
    assert_contains("version.properties", "VERSION_NAME=");
    assert_contains("version.properties", "BUILD_NUMBER=");
    assert_contains("apps/android/build.gradle.kts", "version.properties");
    assert_contains("apps/android/build.gradle.kts", "VERSION_NAME");
    assert_contains("apps/android/build.gradle.kts", "BUILD_NUMBER");
    assert_contains(
        "scripts/bump-version.sh",
        "scripts/bump-version.sh <version-name> [build-number]",
    );
    assert_contains(
        "apps/android/src/main/java/com/hippo2cat/smspusher/MainActivity.java",
        "appVersionName()",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "version.properties",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "BUNDLE_SHORT_VERSION=\"${BUNDLE_SHORT_VERSION:-${VERSION_NAME:-0.1.0}}\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "BUNDLE_VERSION=\"${BUNDLE_VERSION:-${BUILD_NUMBER:-1}}\"",
    );
    assert_contains(
        ".github/workflows/android-release.yml",
        "echo \"version_name=${VERSION_NAME}\"",
    );
    assert_contains(
        ".github/workflows/macos-release.yml",
        "echo \"version_name=${VERSION_NAME}\"",
    );
    assert_contains(
        ".github/workflows/windows-release.yml",
        "echo \"version_name=${VERSION_NAME}\"",
    );

    let version_file = std::fs::read_to_string(repo_root().join("version.properties")).unwrap();
    assert!(!version_file.contains("ANDROID_VERSION_NAME"));
    assert!(!version_file.contains("ANDROID_VERSION_CODE"));
    assert!(!version_file.contains("DESKTOP_VERSION_NAME"));
    assert!(!version_file.contains("DESKTOP_BUILD_NUMBER"));

    let android_build =
        std::fs::read_to_string(repo_root().join("apps/android/build.gradle.kts")).unwrap();
    assert!(android_build.contains("versionName = appVersionName"));
    assert!(android_build.contains("versionCode = androidVersionCode"));
    assert!(!android_build.contains("ANDROID_VERSION_NAME"));
    assert!(!android_build.contains("ANDROID_VERSION_CODE"));
    assert!(!android_build.contains("versionName = \"0."));
    assert!(!android_build.lines().any(|line| {
        let trimmed = line.trim();
        trimmed
            .strip_prefix("versionCode = ")
            .and_then(|value| value.chars().next())
            .is_some_and(|first| first.is_ascii_digit())
    }));

    let main_activity = std::fs::read_to_string(
        repo_root().join("apps/android/src/main/java/com/hippo2cat/smspusher/MainActivity.java"),
    )
    .unwrap();
    assert!(!main_activity.contains("\"v0."));

    let package_script =
        std::fs::read_to_string(repo_root().join("apps/tauri/scripts/package-tauri-macos-app.sh"))
            .unwrap();
    assert!(!package_script.contains("DESKTOP_VERSION_NAME"));
    assert!(!package_script.contains("DESKTOP_BUILD_NUMBER"));
}

#[test]
fn update_manifest_pages_workflow_uses_shared_version_and_manual_dispatch() {
    let path = ".github/workflows/update-manifest-pages.yml";
    assert_file(path);
    assert_contains(path, "name: Update Manifest Pages");
    assert_contains(path, "workflow_dispatch:");
    assert_contains(path, "source version.properties");
    assert_contains(path, "echo \"version_name=${VERSION_NAME}\"");
    assert_contains(path, "updates/stable/latest.json");
    assert_contains(path, "actions/upload-pages-artifact@v4");
    assert_contains(path, "actions/deploy-pages@v4");
    assert_contains(path, "permissions:");
    assert_contains(path, "pages: write");
    assert_contains(path, "id-token: write");
    assert_contains(path, "SmsPusher-${VERSION_NAME}.dmg");
    assert_contains(path, "SmsPusher-${VERSION_NAME}-windows-x64.exe");
    assert_contains(path, "id: release_assets");
    assert_contains(path, "windows_asset_available=true");
    assert_contains(
        path,
        "Windows asset is not present; manifest will only advertise macOS.",
    );

    let workflow = std::fs::read_to_string(repo_root().join(path)).unwrap_or_default();
    assert!(workflow.contains("on:\n  workflow_dispatch:"));
    assert!(!workflow.contains("\n  release:"));
    assert!(!workflow.contains("\n  push:"));
    assert!(!workflow.contains("workflow_run"));
    assert!(!workflow.contains(
        "grep -Fx \"SmsPusher-${VERSION_NAME}-windows-x64.exe\" release-assets.txt"
    ));
}

#[test]
fn tauri_macos_package_script_declares_bundle_contract() {
    assert_file("apps/tauri/scripts/package-tauri-macos-app.sh");
    assert_file("apps/tauri/resources/AppIcon.icns");
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "APP_NAME=\"${APP_NAME:-SmsPusher}\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "EXECUTABLE_NAME=\"${EXECUTABLE_NAME:-SmsPusher}\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "BUNDLE_IDENTIFIER=\"${BUNDLE_IDENTIFIER:-com.jbz.smspusher}\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "APP_BUNDLE=\"$OUTPUT_DIR/$APP_NAME.app\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "cargo build --manifest-path \"$SRC_TAURI_DIR/Cargo.toml\" $CARGO_PROFILE_FLAG --bin \"$EXECUTABLE_NAME\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "cp \"$BUILT_EXECUTABLE\" \"$MACOS_BUNDLE_DIR/$EXECUTABLE_NAME\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "cp \"$APP_ICON\" \"$RESOURCES_DIR/AppIcon.icns\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "<key>CFBundleExecutable</key>",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "<key>CFBundleIdentifier</key>",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "<key>LSUIElement</key>",
    );
    assert_contains("apps/tauri/scripts/package-tauri-macos-app.sh", "<true/>");
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "<string>_smspusher._tcp</string>",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "SmsPusher receives SMS messages from your paired Android phone on the local network.",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "printf \"APPL????\" > \"$CONTENTS_DIR/PkgInfo\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "codesign --force --deep --sign - \"$APP_BUNDLE\"",
    );
    assert_contains(
        "apps/tauri/scripts/package-tauri-macos-app.sh",
        "\"$TAURI_DIR\"/build/package/*.app|\"$TAURI_DIR\"/build/test-package/*.app|/private/tmp/*.app|/tmp/*.app",
    );
}
