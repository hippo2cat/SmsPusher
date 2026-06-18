use transport_ble::{
    BleCentralAdapter, BleCentralEvent, BleFrameError, BlePeripheral, BleTransportState,
};

#[derive(Default)]
struct FakeCentralAdapter {
    calls: Vec<String>,
    events: Vec<BleCentralEvent>,
    state: BleTransportState,
}

#[async_trait::async_trait]
impl BleCentralAdapter for FakeCentralAdapter {
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
        self.events.push(BleCentralEvent::AckWritten {
            message_id: message_id.to_owned(),
        });
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

#[test]
fn transport_state_uses_service_facing_status_strings() {
    assert_eq!(BleTransportState::Stopped.as_str(), "stopped");
    assert_eq!(BleTransportState::Scanning.as_str(), "scanning");
    assert_eq!(BleTransportState::Connecting.as_str(), "connecting");
    assert_eq!(BleTransportState::Connected.as_str(), "connected");
    assert_eq!(
        BleTransportState::PermissionRequired.as_str(),
        "permission_required"
    );
    assert_eq!(
        BleTransportState::AdapterUnavailable.as_str(),
        "adapter_unavailable"
    );
}

#[tokio::test]
async fn fake_adapter_records_central_operations_in_order() {
    let mut adapter = FakeCentralAdapter::default();

    assert_eq!(adapter.scan().await.unwrap(), BleTransportState::Scanning);
    assert_eq!(
        adapter.connect("phone-1").await.unwrap(),
        BleTransportState::Connecting
    );
    assert_eq!(
        adapter.subscribe_messages().await.unwrap(),
        BleTransportState::Connected
    );
    adapter.write_ack("msg_1").await.unwrap();
    assert_eq!(
        adapter.disconnect().await.unwrap(),
        BleTransportState::Stopped
    );

    assert_eq!(
        adapter.calls,
        vec![
            "scan",
            "connect:phone-1",
            "subscribe_messages",
            "write_ack:msg_1",
            "disconnect"
        ]
    );
    assert_eq!(
        adapter.drain_events(),
        vec![BleCentralEvent::AckWritten {
            message_id: "msg_1".into()
        }]
    );
}

#[test]
fn central_event_can_carry_discovered_peripheral_and_message_payload() {
    let discovered = BleCentralEvent::PeripheralDiscovered(BlePeripheral {
        id: "phone-1".into(),
        name: Some("Test Android Device".into()),
        rssi: Some(-42),
    });
    let message = BleCentralEvent::MessageReceived {
        payload: br#"{"messageId":"msg_1"}"#.to_vec(),
    };

    assert_eq!(
        discovered,
        BleCentralEvent::PeripheralDiscovered(BlePeripheral {
            id: "phone-1".into(),
            name: Some("Test Android Device".into()),
            rssi: Some(-42),
        })
    );
    assert_eq!(
        message,
        BleCentralEvent::MessageReceived {
            payload: br#"{"messageId":"msg_1"}"#.to_vec()
        }
    );
}
