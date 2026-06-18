# SmsPusher

[简体中文](README.zh-CN.md)

SmsPusher is a local-network bridge that forwards new SMS messages from an Android phone to a desktop receiver. The Android app listens for incoming SMS events, queues messages locally, and pushes them to the paired desktop app over the LAN. The desktop app runs as a Tauri tray/menu-bar app, advertises over Bonjour, stores local history, and shows native notifications.

The project is designed for self-hosted personal use. It does not use a cloud relay, expose a public endpoint, sync historical SMS messages, or support replying to SMS.

## Features

- Android foreground listener for new SMS messages.
- Secure LAN pairing with a short-lived pairing code.
- Local retry queue for temporarily unreachable desktop receivers.
- Desktop tray/menu-bar popup built with Tauri, React, and Vite.
- Native desktop notifications with verification-code copy actions.
- Local message history and diagnostics logs.
- English and Simplified Chinese UI resources.

## Repository Layout

- `apps/android`: sideloaded Android app and Android-specific tests.
- `apps/tauri`: desktop tray receiver, React frontend, and Tauri/Rust backend.
- `services/desktop-rust`: shared Rust core, secure transport, LAN server, and BLE proof-of-concept crates.
- `locales`: shared i18n source files used to generate app-specific resources.
- `scripts`: development helpers, version bumping, and i18n generation.
- `third_party/boringssl`: BoringSSL source used by the secure transport implementation.

## Prerequisites

- macOS for the current desktop packaging flow.
- Android Studio JDK, Android SDK, and Android NDK.
- Rust toolchain with Cargo.
- Node.js and npm for the Tauri frontend.
- `cargo-ndk` for building the Android native crypto library.

You can source `scripts/dev-env.sh` during local development, but review it first and adjust paths for your machine:

```bash
source scripts/dev-env.sh
```

## Build

Build the Android debug APK:

```bash
source scripts/dev-env.sh
(cd apps/android && ./gradlew assembleDebug)
```

Build the desktop receiver binary:

```bash
cargo build --manifest-path apps/tauri/src-tauri/Cargo.toml --bin SmsPusher
```

Run the desktop receiver from source:

```bash
cargo run --manifest-path apps/tauri/src-tauri/Cargo.toml --bin SmsPusher
```

Install the Android debug APK:

```bash
adb install apps/android/build/outputs/apk/debug/SmsPusher-debug.apk
```

## Package Releases

Generate a local Android release keystore once:

```bash
apps/android/scripts/generate-android-release-keystore.sh
```

This creates ignored local files:

- `apps/android/release/SmsPusher-release.jks`
- `apps/android/keystore.properties`

Keep both files private and backed up. Future release APK updates must be signed with the same key. If either file is shared or exposed, rotate the release key before distributing another build.

Build the signed release APK:

```bash
apps/android/scripts/build-android-release.sh
```

Package the macOS app:

```bash
apps/tauri/scripts/package-tauri-macos-app.sh
```

The app bundle is written to `apps/tauri/build/package/SmsPusher.app`.

## Version Management

Shared version metadata lives in `version.properties`, but Android and desktop versions are managed independently:

```properties
ANDROID_VERSION_NAME=1.0.0
ANDROID_VERSION_CODE=7
DESKTOP_VERSION_NAME=1.0.0
DESKTOP_BUILD_NUMBER=7
```

Use the version script instead of editing app manifests directly:

```bash
scripts/bump-version.sh android 0.4.0
scripts/bump-version.sh desktop 0.4.0
scripts/bump-version.sh all 0.4.0
```

The desktop command also syncs `apps/tauri/package.json`, `package-lock.json`, `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml`, and `src-tauri/Cargo.lock`.

## Test

```bash
source scripts/dev-env.sh
(cd apps/android && ./gradlew testDebugUnitTest)
cargo test --manifest-path services/desktop-rust/Cargo.toml
cargo test --manifest-path apps/tauri/src-tauri/Cargo.toml
```

Focused BLE proof-of-concept checks:

```bash
(cd apps/android && ./gradlew testDebugUnitTest --tests 'com.hippo2cat.smspusher.ble.*')
cargo test --manifest-path services/desktop-rust/Cargo.toml -p transport-ble
cargo test --manifest-path services/desktop-rust/Cargo.toml -p ble-macos
cargo test --manifest-path services/desktop-rust/Cargo.toml -p ble-windows
cargo test --manifest-path services/desktop-rust/Cargo.toml -p smspusher-service --test ble_runtime_tests
```

## Android Lock-Screen Reliability

SmsPusher uses a foreground listener service, but Android Doze and vendor battery policies can still pause network access or stop background work after the phone locks.

For reliable lock-screen forwarding:

- Grant SMS receive, network SMS read, and notification permissions in the Android app.
- In the app permission tab, open the battery policy action and allow SmsPusher to ignore battery optimization.
- On MIUI/HyperOS, also open the MIUI background settings entry and set SmsPusher to unrestricted battery usage, allow autostart, and allow background activity.

Queued messages are retried by the foreground listener when Android allows network access again.

## Privacy and Security

- SMS payloads stay on the Android device and paired desktop receiver.
- Pairing and message delivery are local-network workflows; there is no hosted backend.
- Android signing keys, `keystore.properties`, local app data, diagnostics logs, and generated build output are intentionally ignored by git.
- Do not publish real SMS content, phone numbers, device names, logs, signing keys, or local configuration files in issues or pull requests.

## Contributing

Before opening a pull request:

- Keep changes focused and avoid committing generated build output.
- Run the relevant Android, Rust, or Tauri tests listed above.
- Include screenshots for visible UI changes.
- Use conventional commit style already present in history, such as `feat(android): ...`, `fix(tauri): ...`, or `test: ...`.

The `docs/` directory is ignored and treated as local working notes. Durable project instructions should live in tracked files such as this README, scripts, or source-level documentation.

## License

SmsPusher source code is licensed under the GNU Affero General Public License v3.0 only (`AGPL-3.0-only`). See `LICENSE`.

Third-party dependencies and vendored code remain under their respective licenses. See `THIRD_PARTY_NOTICES.md` and the license files under `third_party/`.
