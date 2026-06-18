use base64::{engine::general_purpose::STANDARD, Engine as _};
use keyring_core::{get_default_store, Entry, Error};
use serde::{Deserialize, Serialize};
use smspusher_core::{InMemorySecretStore, SecretStore, SecretStoreError};
use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

pub const LOCAL_SECRET_FILE_NAME: &str = "secrets.json";

pub enum AppSecretStore {
    LocalFile(LocalFileSecretStore),
    Keyring(KeyringSecretStore),
    Memory(InMemorySecretStore),
}

impl AppSecretStore {
    pub fn local_file(path: impl Into<PathBuf>) -> Self {
        Self::LocalFile(LocalFileSecretStore::new(path))
    }

    pub fn keyring() -> Self {
        Self::Keyring(KeyringSecretStore::new())
    }

    pub fn memory() -> Self {
        Self::Memory(InMemorySecretStore::default())
    }
}

impl SecretStore for AppSecretStore {
    fn save_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
        secret: &[u8],
    ) -> Result<(), SecretStoreError> {
        match self {
            Self::LocalFile(store) => store.save_device_secret(device_id, key_id, secret),
            Self::Keyring(store) => store.save_device_secret(device_id, key_id, secret),
            Self::Memory(store) => store.save_device_secret(device_id, key_id, secret),
        }
    }

    fn load_device_secret(
        &self,
        device_id: &str,
        key_id: &str,
    ) -> Result<Vec<u8>, SecretStoreError> {
        match self {
            Self::LocalFile(store) => store.load_device_secret(device_id, key_id),
            Self::Keyring(store) => store.load_device_secret(device_id, key_id),
            Self::Memory(store) => store.load_device_secret(device_id, key_id),
        }
    }

    fn delete_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
    ) -> Result<(), SecretStoreError> {
        match self {
            Self::LocalFile(store) => store.delete_device_secret(device_id, key_id),
            Self::Keyring(store) => store.delete_device_secret(device_id, key_id),
            Self::Memory(store) => store.delete_device_secret(device_id, key_id),
        }
    }
}

#[derive(Debug, Clone)]
pub struct LocalFileSecretStore {
    path: PathBuf,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalSecretFile {
    version: u8,
    device_secrets: BTreeMap<String, String>,
}

impl LocalFileSecretStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    fn key(device_id: &str, key_id: &str) -> String {
        format!("{device_id}:{key_id}")
    }

    fn read_file(&self) -> Result<LocalSecretFile, SecretStoreError> {
        if !self.path.exists() {
            return Ok(LocalSecretFile {
                version: 1,
                device_secrets: BTreeMap::new(),
            });
        }
        let data = fs::read(&self.path).map_err(file_error)?;
        serde_json::from_slice(&data)
            .map_err(|error| SecretStoreError::Unavailable(error.to_string()))
    }

    fn write_file(&self, file: &LocalSecretFile) -> Result<(), SecretStoreError> {
        let file = LocalSecretFile {
            version: 1,
            device_secrets: file.device_secrets.clone(),
        };
        let data = serde_json::to_vec_pretty(&file)
            .map_err(|error| SecretStoreError::Unavailable(error.to_string()))?;
        write_owner_only_file(&self.path, &data).map_err(file_error)
    }
}

impl SecretStore for LocalFileSecretStore {
    fn save_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
        secret: &[u8],
    ) -> Result<(), SecretStoreError> {
        let mut file = self.read_file()?;
        file.version = 1;
        file.device_secrets
            .insert(Self::key(device_id, key_id), STANDARD.encode(secret));
        self.write_file(&file)
    }

    fn load_device_secret(
        &self,
        device_id: &str,
        key_id: &str,
    ) -> Result<Vec<u8>, SecretStoreError> {
        let file = self.read_file()?;
        let value = file
            .device_secrets
            .get(&Self::key(device_id, key_id))
            .ok_or(SecretStoreError::NotFound)?;
        STANDARD
            .decode(value)
            .map_err(|error| SecretStoreError::Unavailable(error.to_string()))
    }

    fn delete_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
    ) -> Result<(), SecretStoreError> {
        let mut file = self.read_file()?;
        file.device_secrets.remove(&Self::key(device_id, key_id));
        self.write_file(&file)
    }
}

pub struct KeyringSecretStore {
    service: String,
}

impl KeyringSecretStore {
    pub fn new() -> Self {
        let _ = keyring::use_native_store(false);
        Self {
            service: "SmsPusher".to_owned(),
        }
    }

    fn entry(&self, device_id: &str, key_id: &str) -> Result<Entry, SecretStoreError> {
        let store = get_default_store().ok_or_else(|| {
            SecretStoreError::Unavailable("keyring default store is not available".to_owned())
        })?;
        store
            .build(&self.service, &format!("{device_id}:{key_id}"), None)
            .map_err(keyring_error)
    }
}

impl Default for KeyringSecretStore {
    fn default() -> Self {
        Self::new()
    }
}

impl SecretStore for KeyringSecretStore {
    fn save_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
        secret: &[u8],
    ) -> Result<(), SecretStoreError> {
        let value = STANDARD.encode(secret);
        self.entry(device_id, key_id)?
            .set_password(&value)
            .map_err(keyring_error)
    }

    fn load_device_secret(
        &self,
        device_id: &str,
        key_id: &str,
    ) -> Result<Vec<u8>, SecretStoreError> {
        let value = self
            .entry(device_id, key_id)?
            .get_password()
            .map_err(keyring_error)?;
        STANDARD
            .decode(value)
            .map_err(|error| SecretStoreError::Unavailable(error.to_string()))
    }

    fn delete_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
    ) -> Result<(), SecretStoreError> {
        self.entry(device_id, key_id)?
            .delete_credential()
            .map_err(keyring_error)
    }
}

fn keyring_error(error: Error) -> SecretStoreError {
    match error {
        Error::NoEntry => SecretStoreError::NotFound,
        error => SecretStoreError::Unavailable(error.to_string()),
    }
}

fn file_error(error: std::io::Error) -> SecretStoreError {
    SecretStoreError::Unavailable(error.to_string())
}

fn write_owner_only_file(path: &Path, data: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp_path = path.with_file_name(format!(
        "{}.tmp",
        path.file_name()
            .and_then(|name| name.to_str())
            .unwrap_or(LOCAL_SECRET_FILE_NAME)
    ));
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&temp_path)?;
    set_owner_only_permissions(&temp_path)?;
    file.write_all(data)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&temp_path, path)?;
    set_owner_only_permissions(path)?;
    Ok(())
}

#[cfg(unix)]
fn set_owner_only_permissions(path: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;

    fs::set_permissions(path, fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn set_owner_only_permissions(_path: &Path) -> std::io::Result<()> {
    Ok(())
}
