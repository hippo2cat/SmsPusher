# SmsPusher

[English](README.md)

SmsPusher 是一个局域网短信转发工具，用于把 Android 手机收到的新短信推送到已配对的桌面端。Android 应用监听新短信事件，将消息写入本地队列，并通过局域网发送给桌面端；桌面端是基于 Tauri 的托盘/菜单栏应用，支持 Bonjour 广播、本地历史记录和系统通知。

本项目面向自托管的个人使用场景。它不使用云端中继、不暴露公网入口、不同步历史短信，也不支持回复短信。

## 功能特性

- Android 前台监听服务，用于处理新短信。
- 使用短时效配对码完成局域网安全配对。
- 本地重试队列，处理桌面端临时不可达的情况。
- 基于 Tauri、React 和 Vite 的桌面托盘/菜单栏弹窗。
- 原生桌面通知，支持复制验证码操作。
- 本地消息历史和诊断日志。
- 英文和简体中文界面资源。

## 仓库结构

- `apps/android`：可侧载安装的 Android 应用和 Android 相关测试。
- `apps/tauri`：桌面托盘接收端、React 前端和 Tauri/Rust 后端。
- `services/desktop-rust`：共享 Rust 核心、加密传输、局域网服务和 BLE 概念验证 crate。
- `locales`：共享 i18n 源文件，用于生成各端应用资源。
- `scripts`：开发辅助脚本、版本升级和 i18n 生成工具。
- `third_party/boringssl`：安全传输实现使用的 BoringSSL 源码。

## 环境要求

- macOS，用于当前桌面端打包流程。
- Android Studio JDK、Android SDK 和 Android NDK。
- Rust 工具链和 Cargo。
- Node.js 和 npm，用于 Tauri 前端。
- `cargo-ndk`，用于构建 Android 原生加密库。

本地开发时可以加载 `scripts/dev-env.sh`，但请先检查并按你的机器调整路径：

```bash
source scripts/dev-env.sh
```

## 构建

构建 Android debug APK：

```bash
source scripts/dev-env.sh
(cd apps/android && ./gradlew assembleDebug)
```

构建桌面接收端二进制：

```bash
cargo build --manifest-path apps/tauri/src-tauri/Cargo.toml --bin SmsPusher
```

从源码运行桌面接收端：

```bash
cargo run --manifest-path apps/tauri/src-tauri/Cargo.toml --bin SmsPusher
```

安装 Android debug APK：

```bash
adb install apps/android/build/outputs/apk/debug/SmsPusher-debug.apk
```

## 打包发布

首次本地生成 Android release 签名：

```bash
apps/android/scripts/generate-android-release-keystore.sh
```

该命令会创建以下已被 git 忽略的本地文件：

- `apps/android/release/SmsPusher-release.jks`
- `apps/android/keystore.properties`

请妥善私密保存并备份这两个文件。后续 release APK 更新必须使用同一个签名。如果文件泄露，请在分发下一版前轮换 release 签名。

构建已签名的 release APK：

```bash
apps/android/scripts/build-android-release.sh
```

打包 macOS 应用：

```bash
apps/tauri/scripts/package-tauri-macos-app.sh
```

应用包会输出到 `apps/tauri/build/package/SmsPusher.app`。

## 版本管理

共享版本元数据位于 `version.properties`；Android 和桌面端使用同一个版本号和构建号：

```properties
VERSION_NAME=1.0.0
BUILD_NUMBER=7
```

请使用版本脚本，不要直接修改各端 manifest：

```bash
scripts/bump-version.sh 0.4.0
scripts/bump-version.sh 0.4.0 12
```

脚本还会同步 `apps/tauri/package.json`、`package-lock.json`、`src-tauri/tauri.conf.json`、`src-tauri/Cargo.toml` 和 `src-tauri/Cargo.lock`。

## 测试

```bash
source scripts/dev-env.sh
(cd apps/android && ./gradlew testDebugUnitTest)
cargo test --manifest-path services/desktop-rust/Cargo.toml
cargo test --manifest-path apps/tauri/src-tauri/Cargo.toml
```

BLE 概念验证的定向检查：

```bash
(cd apps/android && ./gradlew testDebugUnitTest --tests 'com.hippo2cat.smspusher.ble.*')
cargo test --manifest-path services/desktop-rust/Cargo.toml -p transport-ble
cargo test --manifest-path services/desktop-rust/Cargo.toml -p ble-macos
cargo test --manifest-path services/desktop-rust/Cargo.toml -p ble-windows
cargo test --manifest-path services/desktop-rust/Cargo.toml -p smspusher-service --test ble_runtime_tests
```

## Android 锁屏可靠性

SmsPusher 使用前台监听服务，但 Android Doze 和厂商电池策略仍可能在锁屏后暂停网络访问或停止后台任务。

为了提高锁屏转发可靠性：

- 在 Android 应用中授予短信接收、网络短信读取和通知权限。
- 在应用权限页打开电池策略入口，允许 SmsPusher 忽略电池优化。
- 在 MIUI/HyperOS 上，额外打开 MIUI 后台设置入口，将 SmsPusher 设置为不限制电池使用、允许自启动并允许后台活动。

已排队的消息会在 Android 再次允许网络访问后由前台监听服务重试。

## 隐私与安全

- 短信内容只保留在 Android 设备和已配对的桌面接收端。
- 配对和消息投递都在局域网内完成，没有托管后端。
- Android 签名密钥、`keystore.properties`、本地应用数据、诊断日志和生成的构建产物都已被 git 忽略。
- 请不要在 issue 或 pull request 中发布真实短信内容、手机号、设备名、日志、签名密钥或本地配置文件。

## 贡献

提交 pull request 前：

- 保持改动聚焦，避免提交生成的构建产物。
- 运行上文列出的相关 Android、Rust 或 Tauri 测试。
- 涉及可见 UI 改动时附上截图。
- 使用仓库历史中已有的 conventional commit 风格，例如 `feat(android): ...`、`fix(tauri): ...` 或 `test: ...`。

`docs/` 目录被 git 忽略，仅作为本地工作笔记。需要长期维护的项目说明应放在本 README、脚本或源码级文档中。

## 许可证

SmsPusher 源代码仅使用 GNU Affero General Public License v3.0 授权（`AGPL-3.0-only`）。详见 `LICENSE`。

第三方依赖和 vendored 代码仍按其各自许可证授权。详见 `THIRD_PARTY_NOTICES.md` 以及 `third_party/` 下的许可证文件。
