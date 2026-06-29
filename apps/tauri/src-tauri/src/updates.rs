pub use crate::app_state::UpdateProxyMode;

use semver::Version;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
};

pub const UPDATE_MANIFEST_URL: &str =
    "https://hippo2cat.github.io/SmsPusher/updates/stable/latest.json";
const UPDATE_STATE_FILE: &str = "update_state.json";
#[cfg(target_os = "macos")]
const CURL_PATH: &str = "/usr/bin/curl";
#[cfg(windows)]
const CURL_PATH: &str = "curl.exe";
#[cfg(target_os = "macos")]
const OPEN_PATH: &str = "/usr/bin/open";
#[cfg(windows)]
const CMD_PATH: &str = "cmd";
const APP_NAME: &str = "SmsPusher";

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct GitHubAsset {
    pub name: String,
    pub browser_download_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct GitHubRelease {
    pub tag_name: String,
    #[serde(default)]
    pub draft: bool,
    #[serde(default)]
    pub prerelease: bool,
    #[serde(default)]
    pub assets: Vec<GitHubAsset>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct UpdateManifest {
    pub version: String,
    #[serde(default, rename = "buildNumber")]
    pub build_number: Option<u64>,
    #[serde(default)]
    pub channel: Option<String>,
    #[serde(default, rename = "releaseNotesUrl")]
    pub release_notes_url: Option<String>,
    pub platforms: UpdateManifestPlatforms,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct UpdateManifestPlatforms {
    pub macos: Option<UpdateManifestAsset>,
    pub windows: Option<UpdateManifestAsset>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct UpdateManifestAsset {
    #[serde(rename = "assetName")]
    pub asset_name: String,
    #[serde(rename = "downloadUrl")]
    pub download_url: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateState {
    pub last_opened_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateCandidate {
    pub version: String,
    pub asset_name: String,
    pub download_url: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum UpdateCheckOutcome {
    UpToDate,
    InstallerOpened {
        version: String,
        #[serde(rename = "assetName")]
        asset_name: String,
    },
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum UpdateCheckProgressEvent {
    Checking,
    UpToDate,
    Downloading {
        version: String,
        #[serde(rename = "assetName")]
        asset_name: String,
        progress: Option<u8>,
    },
    InstallerOpened {
        version: String,
        #[serde(rename = "assetName")]
        asset_name: String,
    },
    Failed {
        message: String,
    },
    Unsupported,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateProxyConfig {
    mode: UpdateProxyMode,
    url: Option<String>,
}

impl UpdateProxyConfig {
    pub fn from_settings(mode: UpdateProxyMode, url: &str) -> Self {
        let trimmed = url.trim();
        let url = if mode == UpdateProxyMode::Manual && !trimmed.is_empty() {
            Some(trimmed.to_owned())
        } else {
            None
        };
        Self { mode, url }
    }

    pub fn mode(&self) -> UpdateProxyMode {
        self.mode
    }

    pub fn url(&self) -> Option<&str> {
        self.url.as_deref()
    }
}

impl Default for UpdateProxyConfig {
    fn default() -> Self {
        Self {
            mode: UpdateProxyMode::None,
            url: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopUpdatePlatform {
    Macos,
    Windows,
}

impl DesktopUpdatePlatform {
    fn installer_file_name(self, version: &str) -> String {
        match self {
            DesktopUpdatePlatform::Macos => format!("SmsPusher-{version}.dmg"),
            DesktopUpdatePlatform::Windows => format!("SmsPusher-{version}-windows-x64.exe"),
        }
    }

    fn installer_extension(self) -> &'static str {
        match self {
            DesktopUpdatePlatform::Macos => ".dmg",
            DesktopUpdatePlatform::Windows => ".exe",
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum UpdateCheckError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("utf8 error: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),
    #[error("{action} failed with status {status}: {stderr}")]
    CommandFailed {
        action: &'static str,
        status: String,
        stderr: String,
    },
}

pub fn start_desktop_update_check(data_dir: PathBuf) {
    start_desktop_update_check_with_proxy(data_dir, UpdateProxyConfig::default());
}

pub fn start_desktop_update_check_with_proxy(data_dir: PathBuf, proxy: UpdateProxyConfig) {
    #[cfg(target_os = "macos")]
    {
        start_platform_update_check(data_dir, DesktopUpdatePlatform::Macos, "macOS", proxy);
    }

    #[cfg(windows)]
    {
        start_platform_update_check(data_dir, DesktopUpdatePlatform::Windows, "Windows", proxy);
    }

    #[cfg(not(any(target_os = "macos", windows)))]
    {
        let _ = (data_dir, proxy);
    }
}

pub fn run_desktop_update_check_with_proxy(
    data_dir: PathBuf,
    proxy: UpdateProxyConfig,
) -> Result<UpdateCheckOutcome, UpdateCheckError> {
    run_desktop_update_check_with_proxy_and_progress(data_dir, proxy, |_| {})
}

pub fn run_desktop_update_check_with_proxy_and_progress<F>(
    data_dir: PathBuf,
    proxy: UpdateProxyConfig,
    on_progress: F,
) -> Result<UpdateCheckOutcome, UpdateCheckError>
where
    F: Fn(UpdateCheckProgressEvent),
{
    on_progress(UpdateCheckProgressEvent::Checking);

    #[cfg(target_os = "macos")]
    {
        return run_update_check(&data_dir, DesktopUpdatePlatform::Macos, &proxy, on_progress);
    }

    #[cfg(windows)]
    {
        return run_update_check(
            &data_dir,
            DesktopUpdatePlatform::Windows,
            &proxy,
            on_progress,
        );
    }

    #[cfg(not(any(target_os = "macos", windows)))]
    {
        let _ = (data_dir, proxy);
        on_progress(UpdateCheckProgressEvent::Unsupported);
        Ok(UpdateCheckOutcome::Unsupported)
    }
}

#[allow(dead_code)]
pub fn start_macos_update_check(data_dir: PathBuf) {
    #[cfg(target_os = "macos")]
    start_platform_update_check(
        data_dir,
        DesktopUpdatePlatform::Macos,
        "macOS",
        UpdateProxyConfig::default(),
    );

    #[cfg(not(target_os = "macos"))]
    {
        let _ = data_dir;
    }
}

pub fn select_update_candidate(
    current_version: &str,
    state: &UpdateState,
    release: &GitHubRelease,
) -> Option<UpdateCandidate> {
    select_update_candidate_for_platform(
        current_version,
        state,
        release,
        DesktopUpdatePlatform::Macos,
    )
}

pub fn select_update_candidate_for_platform(
    current_version: &str,
    state: &UpdateState,
    release: &GitHubRelease,
    platform: DesktopUpdatePlatform,
) -> Option<UpdateCandidate> {
    if release.draft || release.prerelease {
        return None;
    }

    let release_version_text = normalize_tag(&release.tag_name);
    let release_version = Version::parse(&release_version_text).ok()?;
    let current_version = Version::parse(current_version).ok()?;

    if !release_version.pre.is_empty() || release_version <= current_version {
        return None;
    }

    if state.last_opened_version.as_deref() == Some(release_version_text.as_str()) {
        return None;
    }

    let asset = select_installer_asset(&release.assets, &release_version_text, platform)?;
    Some(UpdateCandidate {
        version: release_version_text,
        asset_name: asset.name.clone(),
        download_url: asset.browser_download_url.clone(),
    })
}

pub fn select_update_candidate_from_manifest_json(
    current_version: &str,
    state: &UpdateState,
    manifest_json: &str,
    platform: DesktopUpdatePlatform,
) -> Result<Option<UpdateCandidate>, serde_json::Error> {
    let manifest: UpdateManifest = serde_json::from_str(manifest_json)?;
    Ok(select_update_candidate_from_manifest(
        current_version,
        state,
        &manifest,
        platform,
    ))
}

pub fn select_update_candidate_from_manifest(
    current_version: &str,
    state: &UpdateState,
    manifest: &UpdateManifest,
    platform: DesktopUpdatePlatform,
) -> Option<UpdateCandidate> {
    let release_version_text = normalize_tag(&manifest.version);
    let release_version = Version::parse(&release_version_text).ok()?;
    let current_version = Version::parse(current_version).ok()?;

    if !release_version.pre.is_empty() || release_version <= current_version {
        return None;
    }

    if state.last_opened_version.as_deref() == Some(release_version_text.as_str()) {
        return None;
    }

    let asset = manifest.platforms.asset_for_platform(platform)?;
    Some(UpdateCandidate {
        version: release_version_text,
        asset_name: asset.asset_name.clone(),
        download_url: asset.download_url.clone(),
    })
}

pub fn update_download_path(
    data_dir: &Path,
    platform: DesktopUpdatePlatform,
    version: &str,
) -> PathBuf {
    let updates_dir = data_dir.join("updates");
    updates_dir.join(platform.installer_file_name(version))
}

#[cfg(any(target_os = "macos", windows))]
fn start_platform_update_check(
    data_dir: PathBuf,
    platform: DesktopUpdatePlatform,
    platform_name: &'static str,
    proxy: UpdateProxyConfig,
) {
    if let Err(error) = thread::Builder::new()
        .name("smspusher-update-check".into())
        .spawn(move || {
            if let Err(error) = run_update_check(&data_dir, platform, &proxy, |_| {}) {
                tracing::warn!(error = %error, platform = platform_name, "desktop update check failed");
            }
        })
    {
        tracing::warn!(error = %error, platform = platform_name, "failed to start desktop update check thread");
    }
}

#[cfg(any(target_os = "macos", windows))]
fn run_update_check(
    data_dir: &Path,
    platform: DesktopUpdatePlatform,
    proxy: &UpdateProxyConfig,
    on_progress: impl Fn(UpdateCheckProgressEvent),
) -> Result<UpdateCheckOutcome, UpdateCheckError> {
    let state_path = data_dir.join(UPDATE_STATE_FILE);
    let state = load_update_state(&state_path)?;
    let manifest = fetch_update_manifest(proxy)?;

    let Some(candidate) = select_update_candidate_from_manifest(
        env!("CARGO_PKG_VERSION"),
        &state,
        &manifest,
        platform,
    ) else {
        on_progress(UpdateCheckProgressEvent::UpToDate);
        return Ok(UpdateCheckOutcome::UpToDate);
    };

    let installer_path = update_download_path(data_dir, platform, &candidate.version);
    let version = candidate.version.clone();
    let asset_name = candidate.asset_name.clone();
    on_progress(UpdateCheckProgressEvent::Downloading {
        version: version.clone(),
        asset_name: asset_name.clone(),
        progress: Some(0),
    });
    download_installer_with_progress(
        &candidate.download_url,
        &installer_path,
        proxy,
        |progress| {
            on_progress(UpdateCheckProgressEvent::Downloading {
                version: version.clone(),
                asset_name: asset_name.clone(),
                progress: Some(progress),
            });
        },
    )?;
    open_installer(platform, &installer_path)?;
    on_progress(UpdateCheckProgressEvent::InstallerOpened {
        version: candidate.version.clone(),
        asset_name: candidate.asset_name.clone(),
    });
    save_update_state(
        &state_path,
        &UpdateState {
            last_opened_version: Some(candidate.version.clone()),
        },
    )?;
    Ok(UpdateCheckOutcome::InstallerOpened {
        version: candidate.version,
        asset_name: candidate.asset_name,
    })
}

#[cfg(any(target_os = "macos", windows))]
fn load_update_state(path: &Path) -> Result<UpdateState, UpdateCheckError> {
    match fs::read_to_string(path) {
        Ok(content) => Ok(serde_json::from_str(&content)?),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(UpdateState::default()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(any(target_os = "macos", windows))]
fn save_update_state(path: &Path, state: &UpdateState) -> Result<(), UpdateCheckError> {
    let data = serde_json::to_vec_pretty(state)?;
    fs::write(path, data)?;
    Ok(())
}

#[cfg(any(target_os = "macos", windows))]
fn fetch_update_manifest(proxy: &UpdateProxyConfig) -> Result<UpdateManifest, UpdateCheckError> {
    let mut command = Command::new(CURL_PATH);
    for arg in curl_args_for_update_request(proxy) {
        command.arg(arg);
    }
    let output = command.arg(UPDATE_MANIFEST_URL).output()?;

    if !output.status.success() {
        return Err(command_failed(
            "fetch update manifest",
            output.status,
            output.stderr,
        ));
    }

    let json = String::from_utf8(output.stdout)?;
    Ok(serde_json::from_str(&json)?)
}

#[cfg(any(target_os = "macos", windows))]
fn download_installer_with_progress(
    url: &str,
    destination: &Path,
    proxy: &UpdateProxyConfig,
    on_progress: impl Fn(u8),
) -> Result<(), UpdateCheckError> {
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary_destination = destination.with_extension(
        destination
            .extension()
            .and_then(|extension| extension.to_str())
            .map(|extension| format!("{extension}.download"))
            .unwrap_or_else(|| "download".to_owned()),
    );
    match fs::remove_file(&temporary_destination) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }

    let mut command = Command::new(CURL_PATH);
    for arg in curl_args_for_download_request(proxy) {
        command.arg(arg);
    }
    let mut child = command
        .arg("--output")
        .arg(&temporary_destination)
        .arg(url)
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()?;

    let mut stderr_text = String::new();
    let mut last_progress = None;
    if let Some(mut stderr) = child.stderr.take() {
        let mut buffer = [0_u8; 1024];
        loop {
            let bytes_read = stderr.read(&mut buffer)?;
            if bytes_read == 0 {
                break;
            }
            stderr_text.push_str(&String::from_utf8_lossy(&buffer[..bytes_read]));
            for progress in parse_curl_progress_percentages(&stderr_text) {
                if last_progress.map(|last| progress > last).unwrap_or(true) {
                    on_progress(progress);
                    last_progress = Some(progress);
                }
            }
            if stderr_text.len() > 4096 {
                let keep_from = stderr_text
                    .char_indices()
                    .rev()
                    .nth(1024)
                    .map(|(index, _)| index)
                    .unwrap_or(0);
                stderr_text = stderr_text[keep_from..].to_owned();
            }
        }
    }

    let status = child.wait()?;

    if !status.success() {
        let _ = fs::remove_file(&temporary_destination);
        return Err(command_failed(
            "download installer",
            status,
            stderr_text.into_bytes(),
        ));
    }

    match fs::remove_file(destination) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    fs::rename(temporary_destination, destination)?;
    Ok(())
}

#[cfg(any(target_os = "macos", windows))]
fn open_installer(platform: DesktopUpdatePlatform, path: &Path) -> Result<(), UpdateCheckError> {
    match platform {
        DesktopUpdatePlatform::Macos => open_macos_installer(path),
        DesktopUpdatePlatform::Windows => open_windows_installer(path),
    }
}

#[cfg(target_os = "macos")]
fn open_macos_installer(path: &Path) -> Result<(), UpdateCheckError> {
    let output = Command::new(OPEN_PATH).arg(path).output()?;
    if !output.status.success() {
        return Err(command_failed(
            "open macOS installer",
            output.status,
            output.stderr,
        ));
    }
    Ok(())
}

#[cfg(windows)]
fn open_macos_installer(_path: &Path) -> Result<(), UpdateCheckError> {
    unreachable!("macOS installer opener is not available on Windows")
}

#[cfg(windows)]
fn open_windows_installer(path: &Path) -> Result<(), UpdateCheckError> {
    let output = Command::new(CMD_PATH)
        .arg("/C")
        .arg("start")
        .arg("")
        .arg(path)
        .output()?;
    if !output.status.success() {
        return Err(command_failed(
            "open Windows installer",
            output.status,
            output.stderr,
        ));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn open_windows_installer(_path: &Path) -> Result<(), UpdateCheckError> {
    unreachable!("Windows installer opener is not available on macOS")
}

#[cfg(any(target_os = "macos", windows))]
fn command_failed(
    action: &'static str,
    status: std::process::ExitStatus,
    stderr: Vec<u8>,
) -> UpdateCheckError {
    UpdateCheckError::CommandFailed {
        action,
        status: status.to_string(),
        stderr: String::from_utf8_lossy(&stderr).trim().to_owned(),
    }
}

#[cfg(any(target_os = "macos", windows))]
fn user_agent() -> String {
    format!("{APP_NAME}/{}", env!("CARGO_PKG_VERSION"))
}

pub fn curl_args_for_update_request(proxy: &UpdateProxyConfig) -> Vec<String> {
    let mut args = vec![
        "--fail".to_owned(),
        "--location".to_owned(),
        "--silent".to_owned(),
        "--show-error".to_owned(),
        "--user-agent".to_owned(),
        user_agent(),
    ];
    match proxy.mode() {
        UpdateProxyMode::None => {
            args.push("--noproxy".to_owned());
            args.push("*".to_owned());
        }
        UpdateProxyMode::System => {}
        UpdateProxyMode::Manual => {
            if let Some(url) = proxy.url() {
                args.push("--proxy".to_owned());
                args.push(url.to_owned());
            } else {
                args.push("--noproxy".to_owned());
                args.push("*".to_owned());
            }
        }
    }
    args
}

fn curl_args_for_download_request(proxy: &UpdateProxyConfig) -> Vec<String> {
    let mut args = vec![
        "--fail".to_owned(),
        "--location".to_owned(),
        "--show-error".to_owned(),
        "--progress-bar".to_owned(),
        "--user-agent".to_owned(),
        user_agent(),
    ];
    append_proxy_args(&mut args, proxy);
    args
}

fn append_proxy_args(args: &mut Vec<String>, proxy: &UpdateProxyConfig) {
    match proxy.mode() {
        UpdateProxyMode::None => {
            args.push("--noproxy".to_owned());
            args.push("*".to_owned());
        }
        UpdateProxyMode::System => {}
        UpdateProxyMode::Manual => {
            if let Some(url) = proxy.url() {
                args.push("--proxy".to_owned());
                args.push(url.to_owned());
            } else {
                args.push("--noproxy".to_owned());
                args.push("*".to_owned());
            }
        }
    }
}

impl UpdateManifestPlatforms {
    fn asset_for_platform(&self, platform: DesktopUpdatePlatform) -> Option<&UpdateManifestAsset> {
        match platform {
            DesktopUpdatePlatform::Macos => self.macos.as_ref(),
            DesktopUpdatePlatform::Windows => self.windows.as_ref(),
        }
    }
}

pub fn parse_curl_progress_percentages(input: &str) -> Vec<u8> {
    input
        .match_indices('%')
        .filter_map(|(percent_index, _)| {
            let prefix = &input[..percent_index];
            let start = prefix
                .rfind(|character: char| !(character.is_ascii_digit() || character == '.'))
                .map(|index| index + 1)
                .unwrap_or(0);
            let value = prefix[start..].parse::<f32>().ok()?;
            if !(0.0..=100.0).contains(&value) {
                return None;
            }
            Some(value.round() as u8)
        })
        .collect()
}

fn normalize_tag(tag_name: &str) -> String {
    tag_name
        .trim()
        .strip_prefix(['v', 'V'])
        .unwrap_or_else(|| tag_name.trim())
        .to_owned()
}

fn select_installer_asset<'a>(
    assets: &'a [GitHubAsset],
    version: &str,
    platform: DesktopUpdatePlatform,
) -> Option<&'a GitHubAsset> {
    let exact_name = platform.installer_file_name(version);
    let extension = platform.installer_extension();
    assets
        .iter()
        .find(|asset| asset.name == exact_name)
        .or_else(|| {
            assets.iter().find(|asset| {
                let lower_name = asset.name.to_ascii_lowercase();
                lower_name.contains(&APP_NAME.to_ascii_lowercase())
                    && lower_name.ends_with(extension)
                    && asset.name.contains(version)
            })
        })
}
