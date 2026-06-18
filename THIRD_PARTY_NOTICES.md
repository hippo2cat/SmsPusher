# Third-Party Notices

SmsPusher source code is licensed under `AGPL-3.0-only` except where otherwise
noted.

Third-party dependencies, generated package artifacts, and vendored source code
remain under their own licenses. In particular:

- `third_party/boringssl` is distributed under the license files included in
  that directory.
- Rust dependencies are declared in the Cargo manifests and lockfiles under
  `services/desktop-rust`, `apps/tauri/src-tauri`, and `apps/android/rust-crypto`.
- Android dependencies are declared in `apps/android/build.gradle.kts`.
- Tauri frontend dependencies are declared in `apps/tauri/package.json` and
  `apps/tauri/package-lock.json`.

When distributing source or binaries, keep the applicable third-party copyright
notices and license texts with the distribution.
