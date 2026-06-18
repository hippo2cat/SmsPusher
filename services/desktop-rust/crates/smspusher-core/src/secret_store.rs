use std::collections::BTreeMap;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SecretStoreError {
    #[error("secret not found")]
    NotFound,
    #[error("secret store unavailable: {0}")]
    Unavailable(String),
}

pub trait SecretStore {
    fn save_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
        secret: &[u8],
    ) -> Result<(), SecretStoreError>;
    fn load_device_secret(
        &self,
        device_id: &str,
        key_id: &str,
    ) -> Result<Vec<u8>, SecretStoreError>;
    fn delete_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
    ) -> Result<(), SecretStoreError>;
}

#[derive(Default)]
pub struct InMemorySecretStore {
    secrets: BTreeMap<String, Vec<u8>>,
}

impl InMemorySecretStore {
    fn key(device_id: &str, key_id: &str) -> String {
        format!("{device_id}:{key_id}")
    }
}

impl SecretStore for InMemorySecretStore {
    fn save_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
        secret: &[u8],
    ) -> Result<(), SecretStoreError> {
        self.secrets
            .insert(Self::key(device_id, key_id), secret.to_vec());
        Ok(())
    }

    fn load_device_secret(
        &self,
        device_id: &str,
        key_id: &str,
    ) -> Result<Vec<u8>, SecretStoreError> {
        self.secrets
            .get(&Self::key(device_id, key_id))
            .cloned()
            .ok_or(SecretStoreError::NotFound)
    }

    fn delete_device_secret(
        &mut self,
        device_id: &str,
        key_id: &str,
    ) -> Result<(), SecretStoreError> {
        self.secrets.remove(&Self::key(device_id, key_id));
        Ok(())
    }
}
