use crate::{
    runtime::{DesktopService, ServiceError},
    ServiceEventSink,
};
use chrono::{DateTime, Utc};
use smspusher_core::{json, DeviceStore, IncomingMessage, MessageStore};
use transport_ble::{BleCentralAdapter, BleCentralEvent, BleFrameError};

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct BleProcessingSummary {
    pub discovered: usize,
    pub connected: usize,
    pub messages: usize,
    pub acks: usize,
    pub reconnects: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum BleServiceError {
    #[error("invalid BLE payload: {0}")]
    InvalidPayload(String),
    #[error("service error: {0}")]
    Service(#[from] ServiceError),
    #[error("adapter error: {0}")]
    Adapter(#[from] BleFrameError),
}

pub async fn process_ble_events<A, DS, MS, ES, N>(
    adapter: &mut A,
    service: &mut DesktopService<DS, MS, ES, N>,
) -> Result<BleProcessingSummary, BleServiceError>
where
    A: BleCentralAdapter,
    DS: DeviceStore,
    MS: MessageStore,
    ES: ServiceEventSink,
    N: Fn() -> DateTime<Utc>,
{
    let mut summary = BleProcessingSummary::default();
    let events = adapter.drain_events();
    for event in events {
        match event {
            BleCentralEvent::PeripheralDiscovered(peripheral) => {
                adapter.connect(&peripheral.id).await?;
                summary.discovered += 1;
            }
            BleCentralEvent::Connected { .. } => {
                adapter.subscribe_messages().await?;
                summary.connected += 1;
            }
            BleCentralEvent::MessageReceived { payload } => {
                let message: IncomingMessage = json::from_slice(&payload)
                    .map_err(|error| BleServiceError::InvalidPayload(error.to_string()))?;
                let message_id = message.message_id.clone();
                service.accept_message(message)?;
                summary.messages += 1;
                adapter.write_ack(&message_id).await?;
                summary.acks += 1;
            }
            BleCentralEvent::Disconnected { .. } => {
                adapter.scan().await?;
                summary.reconnects += 1;
            }
            BleCentralEvent::StateChanged(_) | BleCentralEvent::AckWritten { .. } => {}
        }
    }
    Ok(summary)
}
