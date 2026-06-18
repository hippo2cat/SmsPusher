use ble_macos::controller::{
    CoreBluetoothAvailability, CoreBluetoothGateway, MacBleCentralController, MacBleError,
};
use transport_ble::{
    chunk_payload, decode_ack, encode_frame, BleCentralAdapter, BleCentralEvent, BleTransportState,
    SMSPUSHER_ACK_CHARACTERISTIC_UUID, SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID,
    SMSPUSHER_SERVICE_UUID,
};

#[derive(Default)]
struct FakeGateway {
    availability: CoreBluetoothAvailability,
    operations: Vec<String>,
    writes: Vec<(String, Vec<u8>)>,
}

#[async_trait::async_trait]
impl CoreBluetoothGateway for FakeGateway {
    async fn availability(&mut self) -> CoreBluetoothAvailability {
        self.availability.clone()
    }

    async fn scan_for_service(&mut self, service_uuid: &str) -> Result<(), MacBleError> {
        self.operations.push(format!("scan:{service_uuid}"));
        Ok(())
    }

    async fn connect(&mut self, peripheral_id: &str) -> Result<(), MacBleError> {
        self.operations.push(format!("connect:{peripheral_id}"));
        Ok(())
    }

    async fn subscribe(&mut self, characteristic_uuid: &str) -> Result<(), MacBleError> {
        self.operations
            .push(format!("subscribe:{characteristic_uuid}"));
        Ok(())
    }

    async fn write(&mut self, characteristic_uuid: &str, data: Vec<u8>) -> Result<(), MacBleError> {
        self.operations.push(format!("write:{characteristic_uuid}"));
        self.writes.push((characteristic_uuid.to_owned(), data));
        Ok(())
    }

    async fn disconnect(&mut self) -> Result<(), MacBleError> {
        self.operations.push("disconnect".into());
        Ok(())
    }
}

#[tokio::test]
async fn denied_authorization_returns_permission_required_without_scanning() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::Unauthorized,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    let state = controller.scan().await.unwrap();

    assert_eq!(state, BleTransportState::PermissionRequired);
    assert_eq!(controller.state(), BleTransportState::PermissionRequired);
    assert!(controller.gateway().operations.is_empty());
    assert_eq!(
        controller.drain_events(),
        vec![BleCentralEvent::StateChanged(
            BleTransportState::PermissionRequired
        )]
    );
}

#[tokio::test]
async fn unavailable_adapter_returns_adapter_unavailable_without_scanning() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOff,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    let state = controller.scan().await.unwrap();

    assert_eq!(state, BleTransportState::AdapterUnavailable);
    assert!(controller.gateway().operations.is_empty());
    assert_eq!(
        controller.drain_events(),
        vec![BleCentralEvent::StateChanged(
            BleTransportState::AdapterUnavailable
        )]
    );
}

#[tokio::test]
async fn powered_on_scan_uses_sms_pusher_service_uuid() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    let state = controller.scan().await.unwrap();

    assert_eq!(state, BleTransportState::Scanning);
    assert_eq!(
        controller.gateway().operations,
        vec![format!("scan:{SMSPUSHER_SERVICE_UUID}")]
    );
    assert_eq!(
        controller.drain_events(),
        vec![BleCentralEvent::StateChanged(BleTransportState::Scanning)]
    );
}

#[tokio::test]
async fn connect_and_connected_event_subscribe_to_message_characteristic() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    assert_eq!(
        controller.connect("phone-1").await.unwrap(),
        BleTransportState::Connecting
    );
    assert_eq!(
        controller.handle_connected("phone-1").await.unwrap(),
        BleTransportState::Connected
    );

    assert_eq!(
        controller.gateway().operations,
        vec![
            "connect:phone-1".to_owned(),
            format!("subscribe:{SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID}")
        ]
    );
    assert_eq!(
        controller.drain_events(),
        vec![
            BleCentralEvent::StateChanged(BleTransportState::Connecting),
            BleCentralEvent::Connected {
                peripheral_id: "phone-1".into()
            },
            BleCentralEvent::StateChanged(BleTransportState::Connected)
        ]
    );
}

#[tokio::test]
async fn message_notifications_emit_payload_only_after_all_chunks_arrive() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);
    let frames = chunk_payload(
        "msg_1",
        br#"{"messageId":"msg_1","body":"hello world"}"#,
        12,
    )
    .unwrap();

    let first = controller
        .handle_message_notification(&encode_frame(&frames[1]).unwrap())
        .unwrap();
    assert!(first.is_none());
    let second = controller
        .handle_message_notification(&encode_frame(&frames[0]).unwrap())
        .unwrap();
    assert!(second.is_none());
    let mut completed = None;
    for frame in frames.iter().skip(2) {
        completed = controller
            .handle_message_notification(&encode_frame(frame).unwrap())
            .unwrap();
    }

    let payload = completed.unwrap();
    assert_eq!(payload, br#"{"messageId":"msg_1","body":"hello world"}"#);
    assert_eq!(
        controller.drain_events(),
        vec![BleCentralEvent::MessageReceived { payload }]
    );
}

#[tokio::test]
async fn write_ack_uses_ack_characteristic_and_emits_event() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    controller.write_ack("msg_1").await.unwrap();

    assert_eq!(
        controller.gateway().operations,
        vec![format!("write:{SMSPUSHER_ACK_CHARACTERISTIC_UUID}")]
    );
    assert_eq!(controller.gateway().writes.len(), 1);
    assert_eq!(
        controller.gateway().writes[0].0,
        SMSPUSHER_ACK_CHARACTERISTIC_UUID
    );
    assert_eq!(
        decode_ack(&controller.gateway().writes[0].1).unwrap(),
        "msg_1"
    );
    assert_eq!(
        controller.drain_events(),
        vec![BleCentralEvent::AckWritten {
            message_id: "msg_1".into()
        }]
    );
}

#[tokio::test]
async fn disconnect_calls_gateway_and_returns_stopped() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    let state = controller.disconnect().await.unwrap();

    assert_eq!(state, BleTransportState::Stopped);
    assert_eq!(controller.gateway().operations, vec!["disconnect"]);
}

#[tokio::test]
async fn controller_can_be_driven_through_shared_central_adapter_trait() {
    let gateway = FakeGateway {
        availability: CoreBluetoothAvailability::PoweredOn,
        ..Default::default()
    };
    let mut controller = MacBleCentralController::new(gateway);

    drive_adapter(&mut controller).await;

    assert_eq!(
        controller.gateway().operations,
        vec![
            format!("scan:{SMSPUSHER_SERVICE_UUID}"),
            "connect:phone-1".to_owned(),
            format!("subscribe:{SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID}"),
            format!("write:{SMSPUSHER_ACK_CHARACTERISTIC_UUID}"),
            "disconnect".to_owned()
        ]
    );
}

async fn drive_adapter<A>(adapter: &mut A)
where
    A: BleCentralAdapter,
{
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
}
