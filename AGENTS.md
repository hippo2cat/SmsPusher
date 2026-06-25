# Repository Guidelines

## Project Structure & Module Organization
This repository contains an Android-to-desktop SMS bridge. `apps/android` is the sideloaded Android app, with Java sources in `src/main/java`, unit tests in `src/test/java`, Android resources in `src/main/res`, and native crypto integration under `rust-crypto` and `src/main/jniLibs`. `apps/tauri` is the desktop tray receiver: React/Vite UI in `src`, Tauri/Rust backend in `src-tauri/src`, tests in `src-tauri/tests`, and packaged assets in `resources`, `icons`, and `dist`. `services/desktop-rust` contains shared Rust crates such as `smspusher-core`, `smspusher-service`, `transport-lan`, and BLE transport prototypes. Shared i18n sources live in `locales`; generated app-specific output should stay in each app.

## Build, Test, and Development Commands
Run `source scripts/dev-env.sh` before Android work to set Java, Android SDK, and Gradle paths. Build Android debug with `(cd apps/android && ./gradlew assembleDebug)`, and run Android unit tests with `(cd apps/android && ./gradlew testDebugUnitTest)`. Build the desktop backend with `cargo build --manifest-path apps/tauri/src-tauri/Cargo.toml --bin SmsPusher`. Run desktop tests with `cargo test --manifest-path apps/tauri/src-tauri/Cargo.toml`, and shared Rust tests with `cargo test --manifest-path services/desktop-rust/Cargo.toml`. In `apps/tauri`, use `npm run dev` for Vite, `npm run build` for typecheck plus production assets, and `npm run i18n:generate` after locale edits.

## Coding Style & Naming Conventions
Java uses 4-space indentation, package `com.hippo2cat.smspusher`, and PascalCase classes. Rust follows `rustfmt`, snake_case functions and modules, and PascalCase types. React/TypeScript uses functional components, PascalCase component names, camelCase variables, and colocated styles/assets when practical.

## Testing Guidelines
Prefer focused tests near the changed module: JUnit/Robolectric in `apps/android/src/test`, Tauri integration tests in `apps/tauri/src-tauri/tests`, and Rust crate tests under each crate’s `tests` directory. Add regression tests for pairing, delivery status, retry behavior, crypto, logging, and i18n changes.

## Commit & Pull Request Guidelines
Use Conventional Commits as seen in history, such as `feat(desktop): ...`, `fix(android): ...`, and `chore(i18n): ...`. Keep commits scoped and buildable. Pull requests should describe behavior changes, list verification commands, link related issues, and include screenshots for UI changes.

## Security & Configuration Tips
Do not commit signing keys, `keystore.properties`, local logs, or device data. Android release signing should remain local-only or use `SMSPUSHER_RELEASE_*` environment variables. Avoid logging SMS bodies, pairing codes, private keys, or tokens.
