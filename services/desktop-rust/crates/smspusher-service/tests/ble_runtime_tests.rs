use chrono::{TimeZone, Utc};
use smspusher_core::{InMemoryDeviceStore, SqliteMessageStore, TokenGenerator};
use smspusher_service::{
    process_ble_events, BleProcessingSummary, BleServiceError, DesktopService,
    DesktopServiceConfig, ServiceEvent, VecEventSink,
};
use transport_ble::{
    BleCentralAdapter, BleCentralEvent, BleFrameError, BlePeripheral, BleTransportState,
};

type TestService = DesktopService<
    InMemoryDeviceStore,
    SqliteMessageStore,
    VecEventSink,
    fn() -> chrono::DateTime<Utc>,
>;

#[derive(Default)]
struct FakeBleAdapter {
    calls: Vec<String>,
    events: Vec<BleCentralEvent>,
    state: BleTransportState,
}

#[async_trait::async_trait]
impl BleCentralAdapter for FakeBleAdapter {
    async fn scan(&mut self) -> Result<BleTransportState, BleFrameError> {
        self.calls.push("scan".into());
        self.state = BleTransportState::Scanning;
        Ok(self.state.clone())
    }

    async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, BleFrameError> {
        self.calls.push(format!("connect:{peripheral_id}"));
        self.state = BleTransportState::Connecting;
        Ok(self.state.clone())
    }

    async fn subscribe_messages(&mut self) -> Result<BleTransportState, BleFrameError> {
        self.calls.push("subscribe_messages".into());
        self.state = BleTransportState::Connected;
        Ok(self.state.clone())
    }

    async fn write_ack(&mut self, message_id: &str) -> Result<(), BleFrameError> {
        self.calls.push(format!("write_ack:{message_id}"));
        Ok(())
    }

    async fn disconnect(&mut self) -> Result<BleTransportState, BleFrameError> {
        self.calls.push("disconnect".into());
        self.state = BleTransportState::Stopped;
        Ok(self.state.clone())
    }

    fn state(&self) -> BleTransportState {
        self.state.clone()
    }

    fn drain_events(&mut self) -> Vec<BleCentralEvent> {
        self.events.drain(..).collect()
    }
}

#[tokio::test]
async fn discovered_peripheral_connects_by_id() {
    let (mut service, _sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![BleCentralEvent::PeripheralDiscovered(BlePeripheral {
            id: "phone-1".into(),
            name: Some("Test Android Device".into()),
            rssi: Some(-50),
        })],
        ..Default::default()
    };

    let summary = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap();

    assert_eq!(summary.discovered, 1);
    assert_eq!(adapter.calls, vec!["connect:phone-1"]);
}

#[tokio::test]
async fn connected_event_subscribes_to_message_notifications() {
    let (mut service, _sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![BleCentralEvent::Connected {
            peripheral_id: "phone-1".into(),
        }],
        ..Default::default()
    };

    let summary = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap();

    assert_eq!(summary.connected, 1);
    assert_eq!(adapter.calls, vec!["subscribe_messages"]);
}

#[tokio::test]
async fn valid_message_payload_is_persisted_and_acked() {
    let (mut service, sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![BleCentralEvent::MessageReceived {
            payload: message_payload("msg_1"),
        }],
        ..Default::default()
    };

    let summary = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap();

    assert_eq!(
        summary,
        BleProcessingSummary {
            messages: 1,
            acks: 1,
            ..Default::default()
        }
    );
    assert_eq!(adapter.calls, vec!["write_ack:msg_1"]);
    assert_eq!(service.list_messages(10).unwrap().len(), 1);
    assert!(sink
        .events()
        .iter()
        .any(|event| matches!(event, ServiceEvent::MessageReceived { message_id, .. } if message_id == "msg_1")));
}

#[tokio::test]
async fn duplicate_message_payload_is_acked_but_stored_once() {
    let (mut service, _sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![
            BleCentralEvent::MessageReceived {
                payload: message_payload("msg_1"),
            },
            BleCentralEvent::MessageReceived {
                payload: message_payload("msg_1"),
            },
        ],
        ..Default::default()
    };

    let summary = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap();

    assert_eq!(summary.messages, 2);
    assert_eq!(summary.acks, 2);
    assert_eq!(adapter.calls, vec!["write_ack:msg_1", "write_ack:msg_1"]);
    assert_eq!(service.list_messages(10).unwrap().len(), 1);
}

#[tokio::test]
async fn invalid_message_payload_returns_error_without_ack() {
    let (mut service, _sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![BleCentralEvent::MessageReceived {
            payload: br#"{"messageId":"msg_1"}"#.to_vec(),
        }],
        ..Default::default()
    };

    let error = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap_err();

    assert!(matches!(error, BleServiceError::InvalidPayload(_)));
    assert!(adapter.calls.is_empty());
}

#[tokio::test]
async fn disconnected_event_restarts_scan_for_reconnect() {
    let (mut service, _sink) = service();
    let mut adapter = FakeBleAdapter {
        events: vec![BleCentralEvent::Disconnected {
            peripheral_id: "phone-1".into(),
        }],
        ..Default::default()
    };

    let summary = process_ble_events(&mut adapter, &mut service)
        .await
        .unwrap();

    assert_eq!(summary.reconnects, 1);
    assert_eq!(adapter.calls, vec!["scan"]);
}

fn service() -> (TestService, VecEventSink) {
    fn now() -> chrono::DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 6, 11, 8, 0, 0).unwrap()
    }

    let sink = VecEventSink::default();
    let service = DesktopService::new_for_tests(
        DesktopServiceConfig {
            service_name: "SmsPusher Test".into(),
            preferred_port: 55515,
            history_limit: 1000,
        },
        InMemoryDeviceStore::default(),
        SqliteMessageStore::open_in_memory(1000).unwrap(),
        TokenGenerator::seeded(44),
        sink.clone(),
        now as fn() -> chrono::DateTime<Utc>,
    );
    (service, sink)
}

fn message_payload(message_id: &str) -> Vec<u8> {
    format!(
        r#"{{
            "messageId":"{message_id}",
            "sender":"TEST-SENDER",
            "body":"test code 135790",
            "receivedAt":"2026-06-11T08:00:00Z",
            "subscriptionId":1,
            "deviceId":"dev_1"
        }}"#
    )
    .into_bytes()
}
