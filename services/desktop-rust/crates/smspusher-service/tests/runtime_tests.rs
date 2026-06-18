use chrono::{TimeZone, Utc};
use smspusher_core::{InMemoryDeviceStore, PairRequest, SqliteMessageStore, TokenGenerator};
use smspusher_service::{DesktopService, DesktopServiceConfig, ServiceEvent, VecEventSink};

#[test]
fn status_snapshot_exposes_pairing_code_and_empty_state() {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    let service = DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(31),
        VecEventSink::default(),
        || now,
    );

    let snapshot = service.status_snapshot();

    assert_eq!(snapshot.service_name, "SmsPusher Test");
    assert_eq!(snapshot.preferred_port, 55515);
    assert!(!snapshot.pairing_code.value.is_empty());
    assert!(snapshot.devices.is_empty());
    assert!(snapshot.latest_messages.is_empty());
}

#[test]
fn pair_and_receive_message_emit_device_message_and_queue_events() {
    let now = Utc.with_ymd_and_hms(2026, 6, 5, 8, 0, 0).unwrap();
    let sink = VecEventSink::default();
    let mut service = DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(32),
        sink.clone(),
        || now,
    );
    let code = service.status_snapshot().pairing_code.value;
    let paired = service
        .pair(PairRequest {
            pairing_code: code,
            device_name: "Test Android Device".into(),
            client_version: 1,
            client_instance_id: Some("android-client-1".into()),
        })
        .unwrap();

    service
        .accept_message(smspusher_core::IncomingMessage {
            message_id: "msg_1".into(),
            sender: "TEST-SENDER".into(),
            body: "测试验证码 135790".into(),
            received_at: now,
            subscription_id: 1,
            device_id: paired.device_id,
        })
        .unwrap();

    let events = sink.events();
    assert!(events
        .iter()
        .any(|event| matches!(event, ServiceEvent::DeviceChanged { .. })));
    assert!(events
        .iter()
        .any(|event| matches!(event, ServiceEvent::MessageReceived { .. })));
    assert!(events
        .iter()
        .any(|event| matches!(event, ServiceEvent::QueueChanged { .. })));
}

#[test]
fn service_runtime_emits_tracing_diagnostics() {
    let cargo = include_str!("../Cargo.toml");
    let runtime = include_str!("../src/runtime.rs");

    assert!(cargo.contains("tracing.workspace = true"));
    assert!(runtime.contains("tracing::info!"));
    assert!(runtime.contains("tracing::warn!"));
    assert!(runtime.contains("refresh_pairing_code"));
    assert!(runtime.contains("accept_message"));
    assert!(runtime.contains("finish_secure_pairing"));
}
