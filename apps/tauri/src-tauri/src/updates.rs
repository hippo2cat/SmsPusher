use semver::Version;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
    thread,
};

const GITHUB_LATEST_RELEASE_URL: &str =
    "https://api.github.com/repos/hippo2cat/AndroidSmsPushToMacos/releases/latest";
const UPDATE_STATE_FILE: &str = "update_state.json";
const CURL_PATH: &str = "/usr/bin/curl";
const OPEN_PATH: &str = "/usr/bin/open";
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

#[derive(Debug, thiserror::Error)]
enum UpdateCheckError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("utf8 error: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),
    #[error("missing home directory")]
    MissingHome,
    #[error("downloads directory is unavailable: {0}")]
    DownloadsUnavailable(String),
    #[error("{action} failed with status {status}: {stderr}")]
    CommandFailed {
        action: &'static str,
        status: String,
        stderr: String,
    },
}

pub fn start_macos_update_check(data_dir: PathBuf) {
    #[cfg(target_os = "macos")]
    {
        if let Err(error) = thread::Builder::new()
            .name("smspusher-update-check".into())
            .spawn(move || {
                if let Err(error) = run_macos_update_check(&data_dir) {
                    tracing::warn!(error = %error, "macOS update check failed");
                }
            })
        {
            tracing::warn!(error = %error, "failed to start macOS update check thread");
        }
    }

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

    let asset = select_dmg_asset(&release.assets, &release_version_text)?;
    Some(UpdateCandidate {
        version: release_version_text,
        asset_name: asset.name.clone(),
        download_url: asset.browser_download_url.clone(),
    })
}

#[cfg(target_os = "macos")]
fn run_macos_update_check(data_dir: &Path) -> Result<(), UpdateCheckError> {
    let state_path = data_dir.join(UPDATE_STATE_FILE);
    let state = load_update_state(&state_path)?;
    let release = fetch_latest_release()?;

    let Some(candidate) = select_update_candidate(env!("CARGO_PKG_VERSION"), &state, &release)
    else {
        return Ok(());
    };

    let dmg_path = download_path(&candidate.version)?;
    download_dmg(&candidate.download_url, &dmg_path)?;
    open_dmg(&dmg_path)?;
    save_update_state(
        &state_path,
        &UpdateState {
            last_opened_version: Some(candidate.version),
        },
    )
}

#[cfg(target_os = "macos")]
fn load_update_state(path: &Path) -> Result<UpdateState, UpdateCheckError> {
    match fs::read_to_string(path) {
        Ok(content) => Ok(serde_json::from_str(&content)?),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(UpdateState::default()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(target_os = "macos")]
fn save_update_state(path: &Path, state: &UpdateState) -> Result<(), UpdateCheckError> {
    let data = serde_json::to_vec_pretty(state)?;
    fs::write(path, data)?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn fetch_latest_release() -> Result<GitHubRelease, UpdateCheckError> {
    let user_agent = user_agent();
    let output = Command::new(CURL_PATH)
        .arg("--fail")
        .arg("--location")
        .arg("--silent")
        .arg("--show-error")
        .arg("--user-agent")
        .arg(user_agent)
        .arg(GITHUB_LATEST_RELEASE_URL)
        .output()?;

    if !output.status.success() {
        return Err(command_failed(
            "fetch latest release",
            output.status,
            output.stderr,
        ));
    }

    let json = String::from_utf8(output.stdout)?;
    Ok(serde_json::from_str(&json)?)
}

#[cfg(target_os = "macos")]
fn download_path(version: &str) -> Result<PathBuf, UpdateCheckError> {
    let home = std::env::var_os("HOME").ok_or(UpdateCheckError::MissingHome)?;
    let downloads = PathBuf::from(home).join("Downloads");
    if !downloads.is_dir() {
        return Err(UpdateCheckError::DownloadsUnavailable(
            downloads.display().to_string(),
        ));
    }
    Ok(downloads.join(format!("{APP_NAME}-{version}.dmg")))
}

#[cfg(target_os = "macos")]
fn download_dmg(url: &str, destination: &Path) -> Result<(), UpdateCheckError> {
    let temporary_destination = destination.with_extension("dmg.download");
    match fs::remove_file(&temporary_destination) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }

    let user_agent = user_agent();
    let output = Command::new(CURL_PATH)
        .arg("--fail")
        .arg("--location")
        .arg("--silent")
        .arg("--show-error")
        .arg("--user-agent")
        .arg(user_agent)
        .arg("--output")
        .arg(&temporary_destination)
        .arg(url)
        .output()?;

    if !output.status.success() {
        let _ = fs::remove_file(&temporary_destination);
        return Err(command_failed("download DMG", output.status, output.stderr));
    }

    fs::rename(temporary_destination, destination)?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn open_dmg(path: &Path) -> Result<(), UpdateCheckError> {
    let output = Command::new(OPEN_PATH).arg(path).output()?;
    if !output.status.success() {
        return Err(command_failed("open DMG", output.status, output.stderr));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
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

#[cfg(target_os = "macos")]
fn user_agent() -> String {
    format!("{APP_NAME}/{}", env!("CARGO_PKG_VERSION"))
}

fn normalize_tag(tag_name: &str) -> String {
    tag_name
        .trim()
        .strip_prefix(['v', 'V'])
        .unwrap_or_else(|| tag_name.trim())
        .to_owned()
}

fn select_dmg_asset<'a>(assets: &'a [GitHubAsset], version: &str) -> Option<&'a GitHubAsset> {
    let exact_name = format!("SmsPusher-{version}.dmg");
    assets
        .iter()
        .find(|asset| asset.name == exact_name)
        .or_else(|| {
            assets.iter().find(|asset| {
                asset.name.to_ascii_lowercase().ends_with(".dmg") && asset.name.contains(version)
            })
        })
}
