use transport_ble::{
    decode_frame, encode_ack, BleCentralAdapter, BleCentralEvent, BleFrameError, BleReassembler,
    BleTransportState, SMSPUSHER_ACK_CHARACTERISTIC_UUID, SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID,
    SMSPUSHER_SERVICE_UUID,
};

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum WinRtBleAvailability {
    #[default]
    PoweredOn,
    PoweredOff,
    Unsupported,
    AccessDenied,
}

#[derive(Debug, thiserror::Error)]
pub enum WinBleError {
    #[error("winrt ble gateway error: {0}")]
    Gateway(String),
    #[error("frame error: {0}")]
    Frame(#[from] BleFrameError),
}

#[async_trait::async_trait]
pub trait WinRtBleGateway {
    async fn availability(&mut self) -> WinRtBleAvailability;
    async fn scan_for_service(&mut self, service_uuid: &str) -> Result<(), WinBleError>;
    async fn connect(&mut self, peripheral_id: &str) -> Result<(), WinBleError>;
    async fn subscribe(&mut self, characteristic_uuid: &str) -> Result<(), WinBleError>;
    async fn write(&mut self, characteristic_uuid: &str, data: Vec<u8>) -> Result<(), WinBleError>;
    async fn disconnect(&mut self) -> Result<(), WinBleError>;
}

pub struct WinRtBleCentralController<G> {
    gateway: G,
    state: BleTransportState,
    events: Vec<BleCentralEvent>,
    reassembler: BleReassembler,
}

impl<G> WinRtBleCentralController<G>
where
    G: WinRtBleGateway,
{
    pub fn new(gateway: G) -> Self {
        Self {
            gateway,
            state: BleTransportState::Stopped,
            events: Vec::new(),
            reassembler: BleReassembler::new(),
        }
    }

    pub async fn scan(&mut self) -> Result<BleTransportState, WinBleError> {
        match self.gateway.availability().await {
            WinRtBleAvailability::PoweredOn => {
                self.gateway
                    .scan_for_service(SMSPUSHER_SERVICE_UUID)
                    .await?;
                self.set_state(BleTransportState::Scanning);
            }
            WinRtBleAvailability::AccessDenied => {
                self.set_state(BleTransportState::PermissionRequired);
            }
            WinRtBleAvailability::PoweredOff | WinRtBleAvailability::Unsupported => {
                self.set_state(BleTransportState::AdapterUnavailable);
            }
        }
        Ok(self.state.clone())
    }

    pub async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, WinBleError> {
        self.gateway.connect(peripheral_id).await?;
        self.set_state(BleTransportState::Connecting);
        Ok(self.state.clone())
    }

    pub async fn handle_connected(
        &mut self,
        peripheral_id: &str,
    ) -> Result<BleTransportState, WinBleError> {
        self.gateway
            .subscribe(SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID)
            .await?;
        self.events.push(BleCentralEvent::Connected {
            peripheral_id: peripheral_id.to_owned(),
        });
        self.set_state(BleTransportState::Connected);
        Ok(self.state.clone())
    }

    pub fn handle_message_notification(
        &mut self,
        data: &[u8],
    ) -> Result<Option<Vec<u8>>, WinBleError> {
        let frame = decode_frame(data)?;
        let payload = self.reassembler.push(frame)?;
        if let Some(payload) = payload.clone() {
            self.events
                .push(BleCentralEvent::MessageReceived { payload });
        }
        Ok(payload)
    }

    pub async fn write_ack(&mut self, message_id: &str) -> Result<(), WinBleError> {
        let ack = encode_ack(message_id)?;
        self.gateway
            .write(SMSPUSHER_ACK_CHARACTERISTIC_UUID, ack)
            .await?;
        self.events.push(BleCentralEvent::AckWritten {
            message_id: message_id.to_owned(),
        });
        Ok(())
    }

    pub async fn disconnect(&mut self) -> Result<BleTransportState, WinBleError> {
        self.gateway.disconnect().await?;
        self.set_state(BleTransportState::Stopped);
        Ok(self.state.clone())
    }

    pub fn state(&self) -> BleTransportState {
        self.state.clone()
    }

    pub fn drain_events(&mut self) -> Vec<BleCentralEvent> {
        self.events.drain(..).collect()
    }

    pub fn gateway(&self) -> &G {
        &self.gateway
    }

    fn set_state(&mut self, state: BleTransportState) {
        if self.state != state {
            self.state = state.clone();
            self.events.push(BleCentralEvent::StateChanged(state));
        }
    }
}

#[async_trait::async_trait]
impl<G> BleCentralAdapter for WinRtBleCentralController<G>
where
    G: WinRtBleGateway + Send,
{
    async fn scan(&mut self) -> Result<BleTransportState, BleFrameError> {
        WinRtBleCentralController::scan(self)
            .await
            .map_err(to_frame_error)
    }

    async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, BleFrameError> {
        WinRtBleCentralController::connect(self, peripheral_id)
            .await
            .map_err(to_frame_error)
    }

    async fn subscribe_messages(&mut self) -> Result<BleTransportState, BleFrameError> {
        self.gateway
            .subscribe(SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID)
            .await
            .map_err(to_frame_error)?;
        self.set_state(BleTransportState::Connected);
        Ok(self.state.clone())
    }

    async fn write_ack(&mut self, message_id: &str) -> Result<(), BleFrameError> {
        WinRtBleCentralController::write_ack(self, message_id)
            .await
            .map_err(to_frame_error)
    }

    async fn disconnect(&mut self) -> Result<BleTransportState, BleFrameError> {
        WinRtBleCentralController::disconnect(self)
            .await
            .map_err(to_frame_error)
    }

    fn state(&self) -> BleTransportState {
        WinRtBleCentralController::state(self)
    }

    fn drain_events(&mut self) -> Vec<BleCentralEvent> {
        WinRtBleCentralController::drain_events(self)
    }
}

fn to_frame_error(error: WinBleError) -> BleFrameError {
    match error {
        WinBleError::Frame(error) => error,
        WinBleError::Gateway(message) => BleFrameError::Adapter(message),
    }
}
