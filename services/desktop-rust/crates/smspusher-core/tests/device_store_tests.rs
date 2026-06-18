use chrono::{TimeZone, Utc};
use serde_json::Value;
use smspusher_core::FileDeviceStore;
use std::fs;

fn swift_reference_seconds(year: i32, month: u32, day: u32) -> f64 {
    let value = Utc.with_ymd_and_hms(year, month, day, 8, 0, 0).unwrap();
    value.timestamp_millis() as f64 / 1000.0 - 978_307_200.0
}

#[test]
fn file_device_store_reads_and_writes_swift_date_encoded_devices() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("devices.json");
    let access_expires = swift_reference_seconds(2026, 6, 6);
    let refresh_expires = swift_reference_seconds(2026, 9, 3);
    fs::write(
        &path,
        format!(
            r#"{{
              "dev_1": {{
                "deviceId": "dev_1",
                "deviceName": "Test Android Device",
                "clientInstanceId": "android-client-1",
                "accessTokenHash": "access-hash",
                "accessTokenExpiresAt": {access_expires},
                "refreshTokenHash": "refresh-hash",
                "refreshTokenExpiresAt": {refresh_expires},
                "revoked": false
              }}
            }}"#
        ),
    )
    .unwrap();

    let mut store = FileDeviceStore::new(path.clone());
    let mut device = store.device("dev_1").unwrap();

    assert_eq!(device.device_name, "Test Android Device");
    assert_eq!(
        device.access_token_expires_at,
        Utc.with_ymd_and_hms(2026, 6, 6, 8, 0, 0).unwrap()
    );
    assert_eq!(
        device.refresh_token_expires_at,
        Utc.with_ymd_and_hms(2026, 9, 3, 8, 0, 0).unwrap()
    );

    device.revoked = true;
    store.update(device);

    let persisted: Value = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
    assert!(persisted["dev_1"]["accessTokenExpiresAt"].is_number());
    assert!(persisted["dev_1"]["refreshTokenExpiresAt"].is_number());

    let reloaded = FileDeviceStore::new(temp.path().join("devices.json"));
    assert!(reloaded.device("dev_1").unwrap().revoked);
}
