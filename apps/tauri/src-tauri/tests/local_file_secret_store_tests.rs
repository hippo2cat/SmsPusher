use smspusher_core::{SecretStore, SecretStoreError};
use smspusher_tauri_lib::keyring_secret_store::LocalFileSecretStore;
use std::fs;

#[test]
fn local_file_secret_store_persists_device_secret_without_keychain() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("secrets.json");
    let mut store = LocalFileSecretStore::new(path.clone());

    store
        .save_device_secret("android-1", "key-1", b"fixture-key-bytes")
        .unwrap();
    let reopened = LocalFileSecretStore::new(path.clone());

    assert_eq!(
        reopened.load_device_secret("android-1", "key-1").unwrap(),
        b"fixture-key-bytes"
    );
    let data = fs::read_to_string(path).unwrap();
    assert!(data.contains(r#""version": 1"#));
    assert!(data.contains(r#""deviceSecrets""#));
    assert!(!data.contains("fixture-key-bytes"));
}

#[test]
fn local_file_secret_store_deletes_only_requested_device_secret() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("secrets.json");
    let mut store = LocalFileSecretStore::new(path.clone());
    store
        .save_device_secret("android-1", "key-1", b"one")
        .unwrap();
    store
        .save_device_secret("android-2", "key-2", b"two")
        .unwrap();

    store.delete_device_secret("android-1", "key-1").unwrap();
    let reopened = LocalFileSecretStore::new(path);

    assert_eq!(
        reopened.load_device_secret("android-1", "key-1"),
        Err(SecretStoreError::NotFound)
    );
    assert_eq!(
        reopened.load_device_secret("android-2", "key-2").unwrap(),
        b"two"
    );
}

#[cfg(unix)]
#[test]
fn local_file_secret_store_writes_secret_file_with_owner_only_permissions() {
    use std::os::unix::fs::PermissionsExt;

    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("secrets.json");
    let mut store = LocalFileSecretStore::new(path.clone());

    store
        .save_device_secret("android-1", "key-1", b"fixture-key-bytes")
        .unwrap();

    let mode = fs::metadata(path).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode, 0o600);
}
