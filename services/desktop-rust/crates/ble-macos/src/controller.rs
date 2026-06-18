use transport_ble::{
    decode_frame, encode_ack, BleCentralAdapter, BleCentralEvent, BleFrameError, BleReassembler,
    BleTransportState, SMSPUSHER_ACK_CHARACTERISTIC_UUID, SMSPUSHER_MESSAGE_CHARACTERISTIC_UUID,
    SMSPUSHER_SERVICE_UUID,
};

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum CoreBluetoothAvailability {
    #[default]
    PoweredOn,
    PoweredOff,
    Unsupported,
    Unauthorized,
}

#[derive(Debug, thiserror::Error)]
pub enum MacBleError {
    #[error("core bluetooth gateway error: {0}")]
    Gateway(String),
    #[error("frame error: {0}")]
    Frame(#[from] BleFrameError),
}

#[async_trait::async_trait]
pub trait CoreBluetoothGateway {
    async fn availability(&mut self) -> CoreBluetoothAvailability;
    async fn scan_for_service(&mut self, service_uuid: &str) -> Result<(), MacBleError>;
    async fn connect(&mut self, peripheral_id: &str) -> Result<(), MacBleError>;
    async fn subscribe(&mut self, characteristic_uuid: &str) -> Result<(), MacBleError>;
    async fn write(&mut self, characteristic_uuid: &str, data: Vec<u8>) -> Result<(), MacBleError>;
    async fn disconnect(&mut self) -> Result<(), MacBleError>;
}

pub struct MacBleCentralController<G> {
    gateway: G,
    state: BleTransportState,
    events: Vec<BleCentralEvent>,
    reassembler: BleReassembler,
}

impl<G> MacBleCentralController<G>
where
    G: CoreBluetoothGateway,
{
    pub fn new(gateway: G) -> Self {
        Self {
            gateway,
            state: BleTransportState::Stopped,
            events: Vec::new(),
            reassembler: BleReassembler::new(),
        }
    }

    pub async fn scan(&mut self) -> Result<BleTransportState, MacBleError> {
        match self.gateway.availability().await {
            CoreBluetoothAvailability::PoweredOn => {
                self.gateway
                    .scan_for_service(SMSPUSHER_SERVICE_UUID)
                    .await?;
                self.set_state(BleTransportState::Scanning);
            }
            CoreBluetoothAvailability::Unauthorized => {
                self.set_state(BleTransportState::PermissionRequired);
            }
            CoreBluetoothAvailability::PoweredOff | CoreBluetoothAvailability::Unsupported => {
                self.set_state(BleTransportState::AdapterUnavailable);
            }
        }
        Ok(self.state.clone())
    }

    pub async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, MacBleError> {
        self.gateway.connect(peripheral_id).await?;
        self.set_state(BleTransportState::Connecting);
        Ok(self.state.clone())
    }

    pub async fn handle_connected(
        &mut self,
        peripheral_id: &str,
    ) -> Result<BleTransportState, MacBleError> {
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
    ) -> Result<Option<Vec<u8>>, MacBleError> {
        let frame = decode_frame(data)?;
        let payload = self.reassembler.push(frame)?;
        if let Some(payload) = payload.clone() {
            self.events
                .push(BleCentralEvent::MessageReceived { payload });
        }
        Ok(payload)
    }

    pub async fn write_ack(&mut self, message_id: &str) -> Result<(), MacBleError> {
        let ack = encode_ack(message_id)?;
        self.gateway
            .write(SMSPUSHER_ACK_CHARACTERISTIC_UUID, ack)
            .await?;
        self.events.push(BleCentralEvent::AckWritten {
            message_id: message_id.to_owned(),
        });
        Ok(())
    }

    pub async fn disconnect(&mut self) -> Result<BleTransportState, MacBleError> {
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
impl<G> BleCentralAdapter for MacBleCentralController<G>
where
    G: CoreBluetoothGateway + Send,
{
    async fn scan(&mut self) -> Result<BleTransportState, BleFrameError> {
        MacBleCentralController::scan(self)
            .await
            .map_err(to_frame_error)
    }

    async fn connect(&mut self, peripheral_id: &str) -> Result<BleTransportState, BleFrameError> {
        MacBleCentralController::connect(self, peripheral_id)
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
        MacBleCentralController::write_ack(self, message_id)
            .await
            .map_err(to_frame_error)
    }

    async fn disconnect(&mut self) -> Result<BleTransportState, BleFrameError> {
        MacBleCentralController::disconnect(self)
            .await
            .map_err(to_frame_error)
    }

    fn state(&self) -> BleTransportState {
        MacBleCentralController::state(self)
    }

    fn drain_events(&mut self) -> Vec<BleCentralEvent> {
        MacBleCentralController::drain_events(self)
    }
}

fn to_frame_error(error: MacBleError) -> BleFrameError {
    match error {
        MacBleError::Frame(error) => error,
        MacBleError::Gateway(message) => BleFrameError::Adapter(message),
    }
}
