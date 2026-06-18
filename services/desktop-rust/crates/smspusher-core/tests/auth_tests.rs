use std::cell::Cell;

use chrono::{Duration, TimeZone, Utc};
use smspusher_core::{
    AuthError, AuthService, InMemoryDeviceStore, PairRequest, RefreshRequest, TokenGenerator,
};

#[test]
fn pairing_with_valid_code_issues_expiring_tokens() {
    let store = InMemoryDeviceStore::default();
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    let mut service = AuthService::new(store, TokenGenerator::seeded(42), || now);
    let code = service.start_pairing();

    let response = service
        .pair(PairRequest {
            pairing_code: code.value.clone(),
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();

    assert!(!response.device_id.is_empty());
    assert!(!response.access_token.is_empty());
    assert!(!response.refresh_token.is_empty());
    assert_eq!(response.access_token_expires_at, now + Duration::days(1));
    assert_eq!(response.refresh_token_expires_at, now + Duration::days(90));
    assert!(service.device_store().device(&response.device_id).is_some());
}

#[test]
fn invalid_pairing_code_fails() {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    let mut service = AuthService::new(
        InMemoryDeviceStore::default(),
        TokenGenerator::seeded(1),
        || now,
    );
    service.start_pairing();

    let error = service
        .pair(PairRequest {
            pairing_code: "000000".into(),
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap_err();

    assert_eq!(error, AuthError::InvalidPairingCode);
}

#[test]
fn refresh_rotates_refresh_token_and_rejects_old_token() {
    let current = Cell::new(Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap());
    let mut service = AuthService::new(
        InMemoryDeviceStore::default(),
        TokenGenerator::seeded(5),
        || current.get(),
    );
    let code = service.start_pairing();
    let paired = service
        .pair(PairRequest {
            pairing_code: code.value,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: None,
        })
        .unwrap();

    current.set(current.get() + Duration::seconds(86_500));
    let refreshed = service
        .refresh(RefreshRequest {
            device_id: paired.device_id.clone(),
            refresh_token: paired.refresh_token.clone(),
        })
        .unwrap();

    assert_ne!(refreshed.access_token, paired.access_token);
    assert_ne!(refreshed.refresh_token, paired.refresh_token);
    assert_eq!(
        service
            .refresh(RefreshRequest {
                device_id: paired.device_id,
                refresh_token: paired.refresh_token,
            })
            .unwrap_err(),
        AuthError::InvalidRefreshToken
    );
}

#[test]
fn same_client_instance_rotates_tokens_without_duplicate_device() {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    let mut service = AuthService::new(
        InMemoryDeviceStore::default(),
        TokenGenerator::seeded(8),
        || now,
    );
    let first_code = service.start_pairing();
    let first = service
        .pair(PairRequest {
            pairing_code: first_code.value,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: Some("android-client-1".into()),
        })
        .unwrap();
    let second_code = service.start_pairing();

    let second = service
        .pair(PairRequest {
            pairing_code: second_code.value,
            device_name: "Test Android Device Renamed".into(),
            client_version: 1,
            client_instance_id: Some("android-client-1".into()),
        })
        .unwrap();

    assert_eq!(second.device_id, first.device_id);
    assert_ne!(second.refresh_token, first.refresh_token);
    assert_eq!(
        service
            .device_store()
            .all_devices()
            .iter()
            .filter(|device| device.active())
            .count(),
        1
    );
    assert_eq!(
        service
            .device_store()
            .device(&first.device_id)
            .unwrap()
            .device_name,
        "Test Android Device Renamed"
    );
}
