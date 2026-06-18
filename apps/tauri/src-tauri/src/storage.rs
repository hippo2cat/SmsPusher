use crate::app_state::AppSettingsSnapshot;
use std::{
    fs,
    path::{Path, PathBuf},
};

pub const SUPPORT_DIRECTORY_NAME: &str = "SmsPusher";
pub const LEGACY_SUPPORT_DIRECTORY_NAME: &str = "AndroidSmsPushToMacos";
pub const SETTINGS_FILE_NAME: &str = "settings.json";
pub const MIGRATED_FILES: [&str; 5] = [
    "devices.json",
    "messages.sqlite",
    "messages.sqlite-shm",
    "messages.sqlite-wal",
    "secrets.json",
];

#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("settings json error: {0}")]
    SettingsJson(#[from] serde_json::Error),
}

pub fn resolve_sms_pusher_data_dir(app_data_dir: PathBuf) -> PathBuf {
    if app_data_dir.file_name().and_then(|name| name.to_str()) == Some(SUPPORT_DIRECTORY_NAME) {
        return app_data_dir;
    }
    app_data_dir
        .parent()
        .map(|parent| parent.join(SUPPORT_DIRECTORY_NAME))
        .unwrap_or_else(|| PathBuf::from(SUPPORT_DIRECTORY_NAME))
}

pub fn prepare_sms_pusher_data_dir(path: &Path) -> Result<(), StorageError> {
    fs::create_dir_all(path)?;
    if let Some(parent) = path.parent() {
        migrate_legacy_support_if_needed(&parent.join(LEGACY_SUPPORT_DIRECTORY_NAME), path)?;
    }
    Ok(())
}

fn migrate_legacy_support_if_needed(legacy: &Path, support: &Path) -> Result<(), StorageError> {
    if !legacy.exists() {
        return Ok(());
    }
    for filename in MIGRATED_FILES {
        let source = legacy.join(filename);
        let destination = support.join(filename);
        if source.exists() && !destination.exists() {
            fs::copy(source, destination)?;
        }
    }
    Ok(())
}

pub fn load_settings(path: &Path) -> Result<AppSettingsSnapshot, StorageError> {
    let settings_path = path.join(SETTINGS_FILE_NAME);
    if !settings_path.exists() {
        return Ok(AppSettingsSnapshot::default());
    }
    let data = fs::read(settings_path)?;
    Ok(serde_json::from_slice(&data)?)
}

pub fn save_settings(path: &Path, settings: &AppSettingsSnapshot) -> Result<(), StorageError> {
    fs::create_dir_all(path)?;
    let data = serde_json::to_vec_pretty(settings)?;
    fs::write(path.join(SETTINGS_FILE_NAME), data)?;
    Ok(())
}
